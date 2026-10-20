import SwiftUI

/// Settings → Products you named once the cabinet is open (the environment is set after bootstrap).
struct NamedProductsEntry: View {
    @Environment(\.appEnvironment) private var environment
    @Environment(\.undoCenter) private var undo

    var body: some View {
        if let environment {
            NamedProductsView(repository: environment.named, undo: undo)
        } else {
            ProgressView()
        }
    }
}
