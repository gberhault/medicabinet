import SwiftUI

/// Stands in for a feature whose epic is not built yet; never a blank screen.
struct FeaturePlaceholderView: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    let systemImage: String

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            Text(message)
        }
    }
}
