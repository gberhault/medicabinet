import SwiftUI

/// A step in progress, announced by VoiceOver as it appears (X3, X4).
struct TransferProgress: View {
    let text: LocalizedStringKey
    let identifier: String

    var body: some View {
        ProgressView { Text(text) }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier(identifier)
    }
}

/// An important statement (the health-data warning, "replaces the whole cabinet"): an icon and text, never colour
/// alone (X3).
struct TransferNotice: View {
    let text: LocalizedStringKey
    let systemImage: String

    var body: some View {
        Label {
            Text(text).fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: systemImage).foregroundStyle(.orange)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A failed step: what happened, Try again.
struct TransferFailure: View {
    let message: LocalizedStringKey
    let identifier: String
    let retry: (() -> Void)?

    var body: some View {
        ContentUnavailableView {
            Label {
                Text(message)
            } icon: {
                Image(systemName: "exclamationmark.triangle")
            }
        } actions: {
            if let retry {
                Button(action: retry) {
                    Text("transfer.export.retry").frame(minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("transfer.retry")
            }
        }
        .accessibilityIdentifier(identifier)
    }
}
