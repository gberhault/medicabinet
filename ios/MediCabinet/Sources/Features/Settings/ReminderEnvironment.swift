import SwiftUI

private struct ReminderModelKey: EnvironmentKey {
    static let defaultValue: ReminderModel? = nil
}

extension EnvironmentValues {
    /// Set by the main tab view; `nil` only outside it (previews, isolated tests).
    var reminderModel: ReminderModel? {
        get { self[ReminderModelKey.self] }
        set { self[ReminderModelKey.self] = newValue }
    }
}
