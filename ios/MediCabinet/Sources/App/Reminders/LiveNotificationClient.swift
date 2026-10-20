import MediCabinetCore
import UserNotifications

/// `UNUserNotificationCenter`, behind `NotificationClient`. The only file (with the delegate) that imports the
/// notification framework.
final class LiveNotificationClient: NotificationClient, @unchecked Sendable {
    private let center: UNUserNotificationCenter

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    func authorization() async -> NotificationAuthorization {
        switch await center.notificationSettings().authorizationStatus {
        case .notDetermined: .notDetermined
        case .authorized, .provisional, .ephemeral: .authorized
        case .denied: .denied
        @unknown default: .denied
        }
    }

    func requestAuthorization() async throws -> Bool {
        try await center.requestAuthorization(options: [.alert, .sound])
    }

    func pendingDigestIDs() async -> [String] {
        await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(NotificationIDs.digestPrefix) }
    }

    func schedule(_ digest: DigestNotification) async throws {
        try await center.add(Self.request(for: digest))
    }

    /// The request of one digest: counts as text, a floating one-shot trigger at 09:00 on the digest day.
    static func request(for digest: DigestNotification) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = digest.title
        content.body = digest.body
        content.sound = .default
        // No time zone on the components: the trigger is floating, so it stays 09:00 wherever the phone travels.
        var when = DateComponents()
        when.year = digest.day.year
        when.month = digest.day.month
        when.day = digest.day.day
        when.hour = ExpiryDigest.minutesSinceMidnight / 60
        when.minute = ExpiryDigest.minutesSinceMidnight % 60
        let trigger = UNCalendarNotificationTrigger(dateMatching: when, repeats: false)
        return UNNotificationRequest(identifier: digest.id, content: content, trigger: trigger)
    }

    func removePending(ids: [String]) async {
        center.removePendingNotificationRequests(withIdentifiers: ids)
    }
}
