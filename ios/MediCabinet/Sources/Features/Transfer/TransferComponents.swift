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
    /// On the message: an identifier on the whole view would replace the button's.
    let identifier: String
    let retry: (() -> Void)?

    var body: some View {
        ContentUnavailableView {
            Label {
                Text(message).accessibilityIdentifier(identifier)
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
    }
}

/// "3 boxes in the cabinet": a counted line, with its plural from the String Catalog.
struct CountLabel: View {
    let key: String
    let count: Int
    let systemImage: String

    var body: some View {
        Label(TransferText.format(key, count), systemImage: systemImage)
    }
}
