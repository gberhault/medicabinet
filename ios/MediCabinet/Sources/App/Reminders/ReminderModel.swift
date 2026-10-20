import Observation

/// Where the monthly reminder stands.
enum ReminderState: Equatable {
    /// Not asked for (or asked for and refused): nothing is scheduled.
    case off
    /// Asked for and the system allows notifications: digests are scheduled.
    case on
    /// Asked for, but the system no longer allows notifications (revoked in Settings). Reminders are not on.
    case blocked
}

/// Presentation state of the reminder setting, shared by the Settings switch and the card on the Expiring tab.
/// Reminders are off until the user turns them on; the system permission is requested only then (N1, N3). The
/// state is read again whenever the app comes back to the foreground, because the permission can change in the
/// system settings (N4).
@MainActor @Observable
final class ReminderModel {
    private(set) var state: ReminderState = .off
    /// What the system says about notifications, as last read.
    private(set) var authorization: NotificationAuthorization = .notDetermined
    /// "Not now" was chosen on the Expiring tab's card.
    private(set) var cardDismissed: Bool
    /// The permission prompt failed (not the same as refused): the user may try again.
    private(set) var requestFailed = false

    private let preferences: any ExpiryPreferences
    private let client: any NotificationClient
    private let openSystemSettings: @MainActor () -> Void

    init(
        preferences: any ExpiryPreferences, client: any NotificationClient,
        openSystemSettings: @escaping @MainActor () -> Void
    ) {
        self.preferences = preferences
        self.client = client
        self.openSystemSettings = openSystemSettings
        cardDismissed = preferences.cardDismissed
    }

    /// Reads the preference and the system permission. Call at launch and whenever the app becomes active.
    func refresh() async {
        authorization = await client.authorization()
        cardDismissed = preferences.cardDismissed
        if preferences.reminderEnabled, authorization == .notDetermined {
            // Turned on in a previous install of the preferences but never granted: it cannot be on.
            preferences.reminderEnabled = false
        }
        state = Self.state(enabled: preferences.reminderEnabled, authorization: authorization)
    }

    private static func state(enabled: Bool, authorization: NotificationAuthorization) -> ReminderState {
        guard enabled else { return .off }
        return authorization == .authorized ? .on : .blocked
    }

    // MARK: Actions

    /// Turns the reminder on: asks the system for permission the first time. Refused (now or before) leaves it off.
    func turnOn() async {
        requestFailed = false
        var current = await client.authorization()
        if current == .notDetermined {
            do {
                current = try await client.requestAuthorization() ? .authorized : .denied
            } catch {
                requestFailed = true
                authorization = await client.authorization()
                state = Self.state(enabled: preferences.reminderEnabled, authorization: authorization)
                return
            }
        }
        authorization = current
        if current == .authorized {
            preferences.reminderEnabled = true
        }
        state = Self.state(enabled: preferences.reminderEnabled, authorization: authorization)
    }

    /// Turns the reminder off and cancels every pending digest at once (N13).
    func turnOff() async {
        preferences.reminderEnabled = false
        state = .off
        await client.removePending(ids: await client.pendingDigestIDs())
    }

    /// "Not now": the card is hidden for good.
    func dismissCard() {
        preferences.cardDismissed = true
        cardDismissed = true
    }

    func openSettings() {
        openSystemSettings()
    }

    // MARK: What the screens show

    /// The Expiring tab's card (N2): reminders are off and "Not now" was not chosen.
    var showsCard: Bool { state == .off && !cardDismissed }

    /// The system refuses notifications and there is something to say about it: after a refusal, and whenever the
    /// setting is blocked.
    var needsSystemSettings: Bool { authorization == .denied && state != .on }
}
