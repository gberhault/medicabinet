/// How early a box counts as "expiring soon". A closed set of choices keeps the setting simple
/// and the notification text predictable.
public enum ExpiryWindow: Int, CaseIterable, Sendable {
    case days30 = 30
    case days60 = 60
    case days90 = 90

    /// Named default of the "expiring soon" setting.
    public static let `default`: ExpiryWindow = .days60

    public var days: Int { rawValue }

    /// A missing or unrecognised stored value falls back to the default, never to a crash.
    public init(storedDays: Int?) {
        self = storedDays.flatMap { ExpiryWindow(rawValue: $0) } ?? .default
    }
}

public struct ExpiryAssessment: Hashable, Sendable {
    public let status: ExpiryStatus
    /// Last valid day minus today: 0 on the expiry day, negative once expired. `nil` only for `.unknown`.
    public let daysLeft: Int?

    public init(status: ExpiryStatus, daysLeft: Int?) {
        self.status = status
        self.daysLeft = daysLeft
    }

    /// The label "expires today" (still usable, counted as expiring soon).
    public var expiresToday: Bool { daysLeft == 0 }
}

public enum ExpiryRule {
    /// The one status rule. A box with no date is `.unknown`, never `.ok`; the expiry day itself is
    /// still usable; month precision runs to the last day of the month.
    public static func assess(_ expiry: ExpiryDate?, today: CalendarDay, window: ExpiryWindow) -> ExpiryAssessment {
        guard let expiry else { return ExpiryAssessment(status: .unknown, daysLeft: nil) }
        let daysLeft = expiry.lastValidCalendarDay.epochDay - today.epochDay
        let status: ExpiryStatus
        if daysLeft < 0 {
            status = .expired
        } else if daysLeft <= window.days {
            status = .expiringSoon
        } else {
            status = .ok
        }
        return ExpiryAssessment(status: status, daysLeft: daysLeft)
    }
}
