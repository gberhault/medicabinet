import MediCabinetCore
import SwiftUI

/// One import or restore, over the tabs: read, check, preview, confirm, replace, result (I1–I10, B5).
struct ImportFlowView: View {
    let request: ImportCoordinator.Request
    let environment: AppEnvironment
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            TransferProgress(text: "transfer.import.reading", identifier: "transfer.import.reading")
                .navigationTitle("transfer.import.title")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("transfer.import.cancel", action: onClose)
                            .accessibilityIdentifier("transfer.import.cancel")
                    }
                }
        }
        .accessibilityIdentifier("transfer.import.screen")
    }
}
