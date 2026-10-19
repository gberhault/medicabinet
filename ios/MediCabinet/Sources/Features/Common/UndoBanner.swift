import SwiftUI

/// The message and its **Undo** button, shown above the tab bar. VoiceOver hears the message when it appears, and
/// it stays while VoiceOver focus is on the button.
struct UndoBanner: View {
    let center: UndoCenter
    @AccessibilityFocusState private var buttonFocused: Bool

    var body: some View {
        if let pending = center.pending {
            HStack(spacing: 12) {
                Text(verbatim: pending.message)
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("undo.message")
                Button("undo.action") {
                    Task { await center.perform() }
                }
                .buttonStyle(.bordered)
                .frame(minWidth: 44, minHeight: 44)
                .accessibilityIdentifier("undo.action")
                .accessibilityFocused($buttonFocused)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 12)
            .padding(.bottom, 4)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .onChange(of: buttonFocused) { _, focused in
                if focused { center.hold() } else { center.resume() }
            }
            .task(id: pending.id) {
                AccessibilityNotification.Announcement(pending.message).post()
            }
        }
    }
}

/// Shows the undo banner above the tab bar. Applied to the root of each tab (not to the `TabView`, whose bottom inset
/// would cover the tab bar).
private struct UndoBannerModifier: ViewModifier {
    @Environment(\.undoCenter) private var center

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom) {
            if let center { UndoBanner(center: center) }
        }
    }
}

extension View {
    func undoBanner() -> some View { modifier(UndoBannerModifier()) }
}
