public struct DigestCounts: Hashable, Sendable {
    public let expired: Int
    public let expiringSoon: Int

    public init(expired: Int, expiringSoon: Int) {
        self.expired = expired
        self.expiringSoon = expiringSoon
    }
}

/// The monthly reminder: what it says and when it is due. A digest day is the 1st of a month at
/// 09:00 local time. Counts are always computed as of the digest day, never of the delivery time, so
/// a late delivery says the same as an on-time one.
public enum ExpiryDigest {
    public static let dayOfMonth = 1
    /// 09:00 local time.
    public static let minutesSinceMidnight = 9 * 60
    /// How many digests the iOS app keeps scheduled — far below the system's pending-notification cap.
    public static let scheduledAhead = 12

    /// `nil` when both counts are zero, so no notification is sent that month. Boxes of unknown expiry and
    /// boxes no longer in the cabinet are not counted.
    public static func counts(boxes: [Box], on digestDay: CalendarDay, window: ExpiryWindow) -> DigestCounts? {
        var expired = 0
        var expiringSoon = 0
        for box in boxes where box.isInCabinet {
            switch ExpiryRule.assess(box.expiry, today: digestDay, window: window).status {
            case .expired: expired += 1
            case .expiringSoon: expiringSoon += 1
            case .ok, .unknown: break
            }
        }
        return expired == 0 && expiringSoon == 0 ? nil : DigestCounts(expired: expired, expiringSoon: expiringSoon)
    }

    /// The next `count` digest days strictly after now (`today` at `minutes` since local midnight).
    public static func upcoming(today: CalendarDay, minutes: Int, count: Int) -> [CalendarDay] {
        var next = isThisMonthsDigestStillToCome(today: today, minutes: minutes)
            ? CalendarDay(year: today.year, month: today.month, day: dayOfMonth)
            : today.firstOfNextMonth()
        var days: [CalendarDay] = []
        while days.count < count {
            days.append(next)
            next = next.firstOfNextMonth()
        }
        return days
    }

    /// The most recent digest day at or before now: the one a late delivery still owes.
    public static func due(today: CalendarDay, minutes: Int) -> CalendarDay {
        if !isThisMonthsDigestStillToCome(today: today, minutes: minutes) {
            return CalendarDay(year: today.year, month: today.month, day: dayOfMonth)
        }
        return today.month == 1
            ? CalendarDay(year: today.year - 1, month: 12, day: dayOfMonth)
            : CalendarDay(year: today.year, month: today.month - 1, day: dayOfMonth)
    }

    private static func isThisMonthsDigestStillToCome(today: CalendarDay, minutes: Int) -> Bool {
        today.day == dayOfMonth && minutes < minutesSinceMidnight
    }
}
