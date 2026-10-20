import MediCabinetCore
import Observation

/// Presentation state of the "Expiring soon" setting: the window the user picked. Every other screen follows the
/// preference, so a change is used at once (W2).
@MainActor @Observable
final class ExpirySettingsModel {
    /// The three choices, in the order Settings lists them.
    static let windowChoices = ExpiryWindow.allCases

    var window: ExpiryWindow {
        didSet {
            guard window != oldValue else { return }
            preferences.window = window
        }
    }

    @ObservationIgnored private let preferences: any ExpiryPreferences

    init(preferences: any ExpiryPreferences) {
        self.preferences = preferences
        window = preferences.window
    }
}
