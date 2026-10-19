import SwiftUI

/// Blocking screen shown when the cabinet cannot be opened. Never offers to delete anything.
struct BootstrapErrorView: View {
    let failure: BootstrapFailure
    let retry: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(titleKey, systemImage: "exclamationmark.triangle")
        } description: {
            Text(messageKey)
        } actions: {
            Button("bootstrap.retry", action: retry)
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("bootstrap.retry")
        }
        .accessibilityIdentifier("bootstrap.error")
    }

    private var titleKey: LocalizedStringKey {
        switch failure {
        case .backup: "bootstrap.error.backup.title"
        case .migration: "bootstrap.error.migration.title"
        case .storage: "bootstrap.error.storage.title"
        }
    }

    private var messageKey: LocalizedStringKey {
        switch failure {
        case .backup: "bootstrap.error.backup.message"
        case .migration: "bootstrap.error.migration.message"
        case .storage: "bootstrap.error.storage.message"
        }
    }
}
