import SwiftUI

/// The Expiring tab's offer of the monthly reminder: **Turn on** asks for the notification permission, **Not now**
/// hides the card for good. When the system already refuses notifications the card says so and offers Open Settings
/// instead of a Turn on that could not work.
struct ReminderCard: View {
    let model: ReminderModel
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label {
                Text("reminder.card.title")
                    .font(.headline)
            } icon: {
                Image(systemName: "bell.badge")
            }
            .accessibilityAddTraits(.isHeader)
            Text(model.needsSystemSettings ? "reminder.settings.denied" : "reminder.card.message")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            buttons
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("reminder.card")
    }

    /// Side by side, one under the other at accessibility text sizes.
    private var buttons: some View {
        let stacked = typeSize.isAccessibilitySize
        let layout = stacked ? AnyLayout(VStackLayout(alignment: .leading)) : AnyLayout(HStackLayout())
        return layout {
            if model.needsSystemSettings {
                Button("reminder.settings.openSettings") { model.openSettings() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("reminder.card.openSettings")
            } else {
                Button("reminder.card.turnOn") { Task { await model.turnOn() } }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("reminder.card.turnOn")
            }
            if !stacked { Spacer() }
            Button("reminder.card.notNow") { model.dismissCard() }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("reminder.card.notNow")
        }
        .frame(minHeight: 44)
    }
}
