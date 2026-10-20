import MediCabinetCore
import Observation
import SwiftUI

/// The automatic backups, newest first (B4).
@MainActor
@Observable
final class BackupsViewModel {
    enum State: Equatable {
        case loading
        case loaded([ArchiveBackupEntry])
        case failed
    }

    private(set) var state: State = .loading
    private let transfer: CabinetTransfer

    init(transfer: CabinetTransfer) {
        self.transfer = transfer
    }

    /// Lists the files off the main actor (each is decoded for its count).
    func load() async {
        if case .loaded = state {} else { state = .loading }
        do {
            state = .loaded(try await transfer.backups())
        } catch {
            Log.archive.error("Listing the automatic backups failed: \(String(describing: type(of: error)))")
            state = .failed
        }
    }

    static func reasonKey(_ reason: BackupReason) -> LocalizedStringKey {
        switch reason {
        case .beforeImport: "transfer.backups.beforeImport"
        case .beforeRestore: "transfer.backups.beforeRestore"
        }
    }
}

/// Settings → Automatic backups: the cabinets replaced by the last imports and restores, each restorable through the
/// import flow (B4, B5). An unreadable file is listed as such, never hidden.
struct BackupsView: View {
    let revision: CabinetRevision
    let onRestore: (ArchiveBackupEntry) -> Void
    @State private var model: BackupsViewModel

    init(transfer: CabinetTransfer, revision: CabinetRevision, onRestore: @escaping (ArchiveBackupEntry) -> Void) {
        self.revision = revision
        self.onRestore = onRestore
        _model = State(initialValue: BackupsViewModel(transfer: transfer))
    }

    var body: some View {
        content
            .navigationTitle("transfer.backups.title")
            .navigationBarTitleDisplayMode(.inline)
            .task { await model.load() }
            // A restore or an import adds a backup: the list follows.
            .onChange(of: revision.value) { Task { await model.load() } }
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .loading:
            TransferProgress(text: "transfer.backups.loading", identifier: "transfer.backups.loading")
        case .failed:
            ContentUnavailableView {
                Label("transfer.backups.error", systemImage: "exclamationmark.triangle")
            } actions: {
                Button {
                    Task { await model.load() }
                } label: {
                    Text("transfer.error.retry").frame(minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("transfer.backups.retry")
            }
        case let .loaded(entries) where entries.isEmpty:
            ContentUnavailableView {
                Label("transfer.backups.empty", systemImage: "externaldrive")
            }
            .accessibilityIdentifier("transfer.backups.empty")
        case let .loaded(entries):
            List {
                Section {
                    ForEach(entries) { entry in
                        BackupRow(entry: entry, onRestore: { onRestore(entry) })
                    }
                } footer: {
                    Text("transfer.backups.footer")
                }
            }
            .accessibilityIdentifier("transfer.backups.list")
        }
    }
}

private struct BackupRow: View {
    let entry: ArchiveBackupEntry
    let onRestore: () -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                details
                Spacer(minLength: 0)
                restore
            }
            VStack(alignment: .leading, spacing: 8) {
                details
                restore
            }
        }
        .frame(minHeight: 44)
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: TransferText.dateTime(entry.takenAt)).font(.headline)
            Text(BackupsViewModel.reasonKey(entry.reason)).font(.subheadline)
            if let summary = entry.summary {
                Text(verbatim: TransferText.format("transfer.backups.boxes", summary.inCabinet))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                Label("transfer.backups.unreadable", systemImage: "exclamationmark.triangle")
                    .font(.subheadline)
                    .accessibilityIdentifier("transfer.backups.unreadable")
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var restore: some View {
        if entry.summary != nil {
            Button(action: onRestore) {
                Text("transfer.backups.restore").frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .accessibilityHint(Text(verbatim: TransferText.format(
                "transfer.backups.restoreTitle", TransferText.dateTime(entry.takenAt))))
            .accessibilityIdentifier("transfer.backups.restore")
        }
    }
}
