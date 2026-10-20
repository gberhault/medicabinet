import MediCabinetCore
import SwiftUI

/// Settings → "Expiring soon": how many days before its date a box counts as expiring soon (W1, W5).
struct ExpirySettingsSection: View {
    @State private var model: ExpirySettingsModel

    init(preferences: any ExpiryPreferences) {
        _model = State(initialValue: ExpirySettingsModel(preferences: preferences))
    }

    var body: some View {
        @Bindable var model = model
        Section {
            Picker(selection: $model.window) {
                ForEach(ExpirySettingsModel.windowChoices, id: \.self) { choice in
                    Text(verbatim: ExpiryText.windowOption(choice)).tag(choice)
                }
            } label: {
                Text("settings.expiryWindow.title")
            }
            .frame(minHeight: 44)
            .accessibilityIdentifier("settings.expiryWindow")
        } footer: {
            Text("settings.expiryWindow.footer")
        }
    }
}

/// Settings → "Monthly reminder": the switch, why it cannot be on when the system refuses notifications, and the way
/// to the system settings (N2–N4).
struct ReminderSettingsSection: View {
    let model: ReminderModel

    var body: some View {
        Section {
            Toggle(isOn: Binding(
                get: { model.state == .on },
                set: { isOn in Task { if isOn { await model.turnOn() } else { await model.turnOff() } } }
            )) {
                Text("reminder.settings.title")
            }
            .frame(minHeight: 44)
            .accessibilityIdentifier("reminder.settings.toggle")
            if let explanation {
                Text(explanation)
                    .font(.subheadline)
                    .accessibilityIdentifier("reminder.settings.explanation")
                Button("reminder.settings.openSettings") { model.openSettings() }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("reminder.settings.openSettings")
            }
        } footer: {
            Text("reminder.settings.footer")
        }
    }

    /// Blocked: it was on and the system turned notifications off. Denied: the system refuses, so it cannot be turned on.
    private var explanation: LocalizedStringKey? {
        if model.state == .blocked { return "reminder.settings.blocked" }
        return model.needsSystemSettings ? "reminder.settings.denied" : nil
    }
}
