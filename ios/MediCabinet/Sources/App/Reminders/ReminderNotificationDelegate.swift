import UserNotifications

/// Answers the system when a digest arrives or is tapped. It must be the notification center's delegate before the
/// first scene appears, so a tap that launches the app is not lost; the app installs it at launch.
final class ReminderNotificationDelegate: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    /// Opens the Expiring tab (N9).
    private let openExpiring: @MainActor @Sendable () -> Void

    init(openExpiring: @escaping @MainActor @Sendable () -> Void) {
        self.openExpiring = openExpiring
        super.init()
    }

    /// Makes this the delegate of `center`. The center keeps only a weak reference: the caller keeps the delegate.
    @MainActor func install(on center: UNUserNotificationCenter = .current()) {
        center.delegate = self
    }

    /// While the app is open the digest still shows as a banner and in the notification list.
    static let foregroundPresentation: UNNotificationPresentationOptions = [.banner, .list, .sound]

    func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        Self.foregroundPresentation
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        await handleTap(on: response.notification.request.identifier)
    }

    /// A tap on a digest opens the Expiring tab; any other notification is none of this app's digests and is ignored.
    func handleTap(on identifier: String) async {
        guard identifier.hasPrefix(NotificationIDs.digestPrefix) else { return }
        await openExpiring()
    }
}
