import MediCabinetCore
import SwiftUI

/// The export, in a sheet: what will be exported and the health-data warning, then the file is made and handed to
/// the share sheet (E2–E9). Cancel exports nothing.
struct ExportSheet: View {
    let model: ExportViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("transfer.export.title")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        if model.state == .saved {
                            Button("transfer.import.done.close") { close() }
                                .accessibilityIdentifier("transfer.export.close")
                        } else if !model.isBusy {
                            Button("transfer.export.cancel") { close() }
                                .accessibilityIdentifier("transfer.export.cancel")
                        }
                    }
                }
        }
        .onChange(of: model.state) { _, state in
            switch state {
            case .idle:
                dismiss()
            case let .sharing(url):
                SharePresenter.present(url) { activity, completed in
                    Task { await model.shareFinished(activity: activity, completed: completed) }
                }
            default:
                break
            }
        }
        .interactiveDismissDisabled(model.isBusy)
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .idle, .summarizing:
            TransferProgress(text: "transfer.export.loading", identifier: "transfer.export.loading")
        case let .confirm(summary):
            ExportSummaryView(summary: summary, onContinue: { Task { await model.confirm() } }, onCancel: close)
        case .writing, .sharing:
            TransferProgress(text: "transfer.export.writing", identifier: "transfer.export.writing")
        case .saved:
            ContentUnavailableView {
                Label("transfer.export.saved", systemImage: "checkmark.circle")
            }
            .accessibilityIdentifier("transfer.export.saved")
        case .failed:
            TransferFailure(message: "transfer.export.failed", identifier: "transfer.export.failed") {
                Task { await model.retry() }
            }
        }
    }

    private func close() {
        model.cancel()
        dismiss()
    }
}

/// The counts and the warning, shown before any file leaves the app (E2, E9).
struct ExportSummaryView: View {
    let summary: CabinetSummary
    let onContinue: () -> Void
    let onCancel: () -> Void

    var body: some View {
        List {
            Section {
                Label(TransferText.format("transfer.export.summary.boxes", summary.inCabinet), systemImage: "cross.case")
                    .accessibilityIdentifier("transfer.export.summary.boxes")
                Label(TransferText.format("transfer.export.summary.removed", summary.removed), systemImage: "clock.arrow.circlepath")
                Label(TransferText.format("transfer.export.summary.locations", summary.locations), systemImage: "mappin.and.ellipse")
                if summary.inCabinet + summary.removed == 0 {
                    Text("transfer.export.summary.empty")
                        .accessibilityIdentifier("transfer.export.summary.empty")
                }
            }
            Section {
                TransferNotice(text: "transfer.export.warning", systemImage: "exclamationmark.shield")
                    .accessibilityIdentifier("transfer.export.warning")
            }
            Section {
                Button(action: onContinue) {
                    Text("transfer.export.continue").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("transfer.export.continue")
                Button(role: .cancel, action: onCancel) {
                    Text("transfer.export.cancel").frame(maxWidth: .infinity, minHeight: 44)
                }
                .accessibilityIdentifier("transfer.export.summaryCancel")
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
        }
    }
}
