import SwiftUI

/// Shown after **Done**: the boxes are already saved; this only tells how the intake went.
struct ScanSummaryView: View {
    let model: ScanViewModel

    var body: some View {
        NavigationStack {
            List {
                if let summary = model.summary, summary.mode == .remove {
                    Text(verbatim: ScanText.format("scan.removal.summary.usedUp", summary.usedUp))
                        .accessibilityIdentifier("summary.usedUp")
                    Text(verbatim: ScanText.format("scan.removal.summary.thrownAway", summary.thrownAway))
                        .accessibilityIdentifier("summary.thrownAway")
                } else if let summary = model.summary {
                    Text(verbatim: ScanText.format("scan.summary.added", summary.added))
                        .accessibilityIdentifier("summary.added")
                    if summary.unknownExpiry > 0 {
                        Label(ScanText.format("scan.summary.unknownExpiry", summary.unknownExpiry), systemImage: "exclamationmark.triangle")
                            .accessibilityIdentifier("summary.unknownExpiry")
                    }
                }
                Text(model.summary?.mode == .remove ? "scan.removal.summary.message" : "scan.summary.message").foregroundStyle(.secondary)
            }
            .navigationTitle(model.summary?.mode == .remove ? "scan.removal.summary.title" : "scan.summary.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("scan.session.close") { model.closeSummary() }.accessibilityIdentifier("summary.close")
                }
            }
        }
    }
}
