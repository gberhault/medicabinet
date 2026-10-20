import MediCabinetCore
import SwiftUI

/// One import or restore, over the tabs: read, check, preview, confirm, replace, result (I1–I10, B5).
struct ImportFlowView: View {
    let onClose: () -> Void
    @State private var model: ImportViewModel

    /// `afterReplace` runs once the cabinet was replaced (the named-products fill, for a version 1 file).
    init(
        request: ImportCoordinator.Request, transfer: CabinetTransfer, revision: CabinetRevision,
        afterReplace: @escaping @MainActor () -> Void, onClose: @escaping () -> Void
    ) {
        self.onClose = onClose
        _model = State(initialValue: ImportViewModel(
            request: request, transfer: transfer, revision: revision, afterReplace: afterReplace
        ))
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        if model.stage != .done && !model.isWorking {
                            Button("transfer.import.cancel", action: onClose)
                                .accessibilityIdentifier("transfer.import.cancel")
                        }
                    }
                }
        }
        .accessibilityAddTraits(.isModal)
        .accessibilityIdentifier("transfer.import.screen")
        // Not a view task: the work must not be cancelled if the view is rebuilt.
        .onAppear { if model.stage == .reading { Task { await model.start() } } }
    }

    private var title: String {
        if let backup = model.restoring {
            return TransferText.format("transfer.backups.restoreTitle", TransferText.dateTime(backup.takenAt))
        }
        return TransferText.string("transfer.import.title")
    }

    @ViewBuilder private var content: some View {
        switch model.stage {
        case .reading:
            TransferProgress(text: "transfer.import.reading", identifier: "transfer.import.reading")
        case .checking:
            TransferProgress(text: "transfer.import.checking", identifier: "transfer.import.checking")
        case let .applying(backingUp):
            TransferProgress(
                text: backingUp ? "transfer.import.backingUp" : "transfer.import.applying",
                identifier: "transfer.import.applying"
            )
        case .preview:
            if let preview = model.preview {
                ImportPreviewView(preview: preview, onReplace: { Task { await model.replace() } }, onCancel: onClose)
            }
        case .done:
            if let result = model.result {
                ImportResultView(result: result, onClose: onClose)
            }
        case let .failed(failure):
            ImportFailureView(
                failure: failure, onRetry: failure.canRetry ? { Task { await model.retry() } } : nil, onClose: onClose
            )
        }
    }
}

/// Why the import stopped, and what can be done (strings.md, "Errors").
struct ImportFailureView: View {
    let failure: ImportFailure
    let onRetry: (() -> Void)?
    let onClose: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label("transfer.error.title", systemImage: "exclamationmark.triangle")
        } description: {
            Text(LocalizedStringKey(failure.messageKey))
                .accessibilityIdentifier("transfer.import.error.message")
        } actions: {
            if let onRetry {
                Button(action: onRetry) {
                    Text("transfer.error.retry").frame(minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("transfer.import.retry")
            }
            Button(action: onClose) {
                Text("transfer.error.close").frame(minHeight: 44)
            }
            .accessibilityIdentifier("transfer.import.close")
        }
    }
}
