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
