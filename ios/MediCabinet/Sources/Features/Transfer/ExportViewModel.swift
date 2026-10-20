import Foundation
import MediCabinetCore
import Observation
import SwiftUI

/// Presentation state of the export (E1–E9, design flow 1): what will be exported and the health-data warning first,
/// then the file is made off the main actor and handed to the share sheet, and deleted when the sheet is done.
@MainActor
@Observable
final class ExportViewModel {
    enum State: Equatable {
        case idle
        /// Counting what will be exported.
        case summarizing
        /// The summary and the warning: nothing has left the app yet.
        case confirm(CabinetSummary)
        /// Making the file.
        case writing
        /// The share sheet shows this temporary file.
        case sharing(URL)
        /// Saved through "Save to Files" (E8).
        case saved
        case failed(Step)
    }

    /// What failed, so Try again repeats that step only.
    enum Step: Equatable {
        case summary
        case file
    }

    /// The share sheet's own activity for Save to Files.
    static let saveToFilesActivity = "com.apple.DocumentManagerUICore.SaveToFiles"

    private(set) var state: State = .idle
    private let transfer: CabinetTransfer
    private let files: ArchiveFiles
    private let clock: any Clock
    private let timeZone: @Sendable () -> TimeZone
    private let announce: @MainActor (String) -> Void

    init(
        transfer: CabinetTransfer, files: ArchiveFiles = .live, clock: any Clock,
        timeZone: @escaping @Sendable () -> TimeZone = { TimeZone.current },
        announce: @escaping @MainActor (String) -> Void = { AccessibilityNotification.Announcement($0).post() }
    ) {
        self.transfer = transfer
        self.files = files
        self.clock = clock
        self.timeZone = timeZone
        self.announce = announce
    }

    /// The summary is shown before anything is written (E2).
    func start() async {
        state = .summarizing
        do {
            state = .confirm(try await transfer.currentSummary())
        } catch {
            Log.archive.error("Export summary failed: \(String(describing: type(of: error)))")
            state = .failed(.summary)
        }
    }

    /// Continue after the warning: one snapshot, encoded and validated, written to `tmp/Export` (E3, E4, E6).
    func confirm() async {
        state = .writing
        announce(TransferText.string("transfer.export.writing"))
        let name = CabinetTransfer.fileName(for: clock.now, in: timeZone())
        let (transfer, files) = (transfer, files)
        do {
            let bytes = try await transfer.exportArchive()
            let url = try await Task.detached(priority: .userInitiated) { try files.writeExport(bytes, named: name) }.value
            guard state == .writing else {
                // Cancelled meanwhile: the file never reaches the share sheet.
                await Task.detached(priority: .utility) { files.deleteExport(url) }.value
                return
            }
            state = .sharing(url)
        } catch {
            Log.archive.error("Export failed: \(Self.kind(of: error))")
            guard state == .writing else { return }
            state = .failed(.file)
            announce(TransferText.string("transfer.export.failed"))
        }
    }

    /// Making or sharing the file: the sheet cannot be swiped away meanwhile.
    var isBusy: Bool {
        switch state {
        case .writing, .sharing: true
        default: false
        }
    }

    func retry() async {
        guard case let .failed(step) = state else { return }
        switch step {
        case .summary: await start()
        case .file: await confirm()
        }
    }

    /// Cancel at any step before the share sheet: nothing is exported.
    func cancel() {
        state = .idle
    }

    /// The share sheet is done, whatever happened: the temporary file goes (E6). Saving to Files is confirmed (E8);
    /// another app confirms its own sharing.
    func shareFinished(activity: String?, completed: Bool) async {
        guard case let .sharing(url) = state else { return }
        let files = files
        await Task.detached(priority: .utility) { files.deleteExport(url) }.value
        if completed && activity == Self.saveToFilesActivity {
            state = .saved
            announce(TransferText.string("transfer.export.saved"))
        } else {
            state = .idle
        }
    }

    /// Only the error kind is logged, never a value (V5, X1).
    private static func kind(of error: any Error) -> String {
        if let error = error as? ArchiveError { return error.kind.rawValue }
        return String(describing: type(of: error))
    }
}
