import Foundation
import MediCabinetCore
import Observation
import SwiftUI

/// What an import reads.
enum ImportSource: Equatable {
    /// A file opened with the app or picked in Settings.
    case file(URL)
    /// An automatic backup to restore (B5).
    case backup(ArchiveBackupEntry)
}

/// Where every import starts (O1, O3–O5, B5): a file opened with the app, a file picked in Settings, or an automatic
/// backup to restore. One flow at a time, shown over the tabs. A file that arrives early waits until the cabinet is
/// open; one that arrives while a form holds unsaved input waits until the form closes (with a banner in it); while
/// the cabinet cannot be opened, the file is refused with that reason.
@MainActor
@Observable
final class ImportCoordinator {
    enum Readiness: Equatable {
        case loading
        case ready
        /// The cabinet could not be opened at launch: nothing can be imported (O5).
        case unavailable
    }

    /// One import flow to show.
    struct Request: Identifiable, Equatable {
        let id = UUID()
        let source: ImportSource

        /// Restoring backs up the current cabinet as "before a restore", importing as "before an import".
        var reason: BackupReason {
            if case .backup = source { return .beforeRestore }
            return .beforeImport
        }
    }

    private(set) var readiness: Readiness = .loading
    /// The flow on screen; `nil` when none.
    private(set) var active: Request?
    /// Requests waiting for the cabinet to open, a form to close or the current flow to end, oldest first.
    private(set) var waiting: [Request] = []
    /// Forms holding unsaved input (scan completion, manual entry, box editors).
    private(set) var busy: Set<UUID> = []
    /// A file was opened while the cabinet storage is unavailable (O5).
    var showsUnavailable = false

    private let files: ArchiveFiles

    init(files: ArchiveFiles = .live) {
        self.files = files
    }

    /// A file is waiting for the current entry to end: forms show the banner.
    var hasWaitingFile: Bool {
        readiness == .ready && !waiting.isEmpty
    }

    /// The files no launch cleanup may delete yet.
    var heldFiles: Set<URL> {
        Set(([active].compactMap { $0 } + waiting).compactMap { request in
            if case let .file(url) = request.source { return url }
            return nil
        })
    }

    // MARK: Requests

    /// A file opened with the app (Files, Mail, AirDrop…): `.onOpenURL`.
    func receive(_ url: URL) {
        guard url.isFileURL else { return }
        if readiness == .unavailable {
            refuse(url)
            return
        }
        enqueue(Request(source: .file(url)))
    }

    /// A file picked with "Import a cabinet".
    func open(_ url: URL) {
        enqueue(Request(source: .file(url)))
    }

    /// "Restore" on an automatic backup.
    func restore(_ entry: ArchiveBackupEntry) {
        enqueue(Request(source: .backup(entry)))
    }

    /// The flow on screen ended (done, cancelled or failed): an opened file's copy is deleted, and the next waiting
    /// request, if any, opens.
    func finish() {
        guard let ended = active else { return }
        active = nil
        discardCopy(of: ended)
        openNext()
    }

    // MARK: Launch and busy forms

    func cabinetOpened() {
        readiness = .ready
        openNext()
    }

    /// Launch failed: every waiting file is refused with the reason.
    func cabinetUnavailable() {
        readiness = .unavailable
        let refused = waiting
        waiting = []
        for request in refused {
            if case let .file(url) = request.source { refuse(url) }
        }
    }

    func beginBusy(_ token: UUID) {
        busy.insert(token)
    }

    func endBusy(_ token: UUID) {
        busy.remove(token)
        openNext()
    }

    // MARK: Private

    private func enqueue(_ request: Request) {
        waiting.append(request)
        openNext()
    }

    private func openNext() {
        guard readiness == .ready, active == nil, busy.isEmpty, !waiting.isEmpty else { return }
        active = waiting.removeFirst()
    }

    private func refuse(_ url: URL) {
        Log.archive.error("An opened file was refused: the cabinet storage is unavailable")
        showsUnavailable = true
        let files = files
        Task.detached(priority: .utility) { files.discardIfInInbox(url) }
    }

    private func discardCopy(of request: Request) {
        guard case let .file(url) = request.source else { return }
        let files = files
        Task.detached(priority: .utility) { files.discardIfInInbox(url) }
    }
}

private struct ImportCoordinatorKey: EnvironmentKey {
    static let defaultValue: ImportCoordinator? = nil
}

extension EnvironmentValues {
    /// Set by the app root; `nil` in previews and in tests of single screens.
    var importCoordinator: ImportCoordinator? {
        get { self[ImportCoordinatorKey.self] }
        set { self[ImportCoordinatorKey.self] = newValue }
    }
}

/// Marks a form holding unsaved input: an opened file waits until it closes, and says so in it (O4).
struct ImportBusyModifier: ViewModifier {
    @Environment(\.importCoordinator) private var coordinator
    @State private var token = UUID()

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top) {
                if coordinator?.hasWaitingFile == true {
                    Label("transfer.import.pendingBanner", systemImage: "doc.badge.clock")
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(.regularMaterial)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("transfer.import.pendingBanner")
                }
            }
            .onAppear { coordinator?.beginBusy(token) }
            .onDisappear { coordinator?.endBusy(token) }
    }
}

extension View {
    /// See `ImportBusyModifier`.
    func importBusy() -> some View {
        modifier(ImportBusyModifier())
    }
}
