import SwiftUI

/// Shown after **Done**: the boxes are already saved; this only tells how the intake went.
struct ScanSummaryView: View {
    let model: ScanViewModel

    var body: some View {
        NavigationStack {
            List {
                if let summary = model.summary {
                    Text(verbatim: ScanText.format("scan.summary.added", summary.added))
                        .accessibilityIdentifier("summary.added")
                    if summary.unknownExpiry > 0 {
                        Label(ScanText.format("scan.summary.unknownExpiry", summary.unknownExpiry), systemImage: "exclamationmark.triangle")
                            .accessibilityIdentifier("summary.unknownExpiry")
                    }
                }
                Text("scan.summary.message").foregroundStyle(.secondary)
            }
            .navigationTitle("scan.summary.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("scan.session.close") { model.closeSummary() }.accessibilityIdentifier("summary.close")
                }
            }
        }
    }
}
