import MediCabinetCore

/// Whether the system lets the app post notifications. Provisional and ephemeral authorisations are not used.
enum NotificationAuthorization: Equatable, Sendable {
    case notDetermined, denied, authorized
}

/// One monthly digest ready to be scheduled: counts only, written for a digest day. It never carries a box name, a
/// location, a lot, a serial number or a date (N6).
struct DigestNotification: Equatable, Sendable {
    /// `expiry-digest-YYYY-MM`.
    let id: String
    /// The digest day (the 1st of the month); the trigger fires at `ExpiryDigest.minutesSinceMidnight` local time.
    let day: CalendarDay
    let title: String
    let body: String
}

/// What the reminders need from the system's notification center. The only implementation that touches the
/// framework is `LiveNotificationClient`; everything else (and every test) works against this protocol.
protocol NotificationClient: Sendable {
    func authorization() async -> NotificationAuthorization
    /// Shows the system prompt (only the first time it is asked) and says whether notifications are now allowed.
    func requestAuthorization() async throws -> Bool
    /// Ids of the pending digest requests (`expiry-digest-…`), whatever else the app has pending.
    func pendingDigestIDs() async -> [String]
    /// Adds the request, or replaces the pending one with the same id.
    func schedule(_ digest: DigestNotification) async throws
    func removePending(ids: [String]) async
}

enum NotificationIDs {
    static let digestPrefix = "expiry-digest-"

    /// `expiry-digest-2027-04`.
    static func digest(for day: CalendarDay) -> String {
        digestPrefix + String(format: "%04d-%02d", day.year, day.month)
    }
}
