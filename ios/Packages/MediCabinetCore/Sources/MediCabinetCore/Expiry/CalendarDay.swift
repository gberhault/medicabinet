import Foundation

/// A calendar date (no time, no zone): what "today" means to every expiry rule. The core never
/// sees a time zone in its rules — the edge turns an instant and a zone into a `CalendarDay` once,
/// then rules use whole-day arithmetic, so both platforms agree to the day.
public struct CalendarDay: Hashable, Comparable, Sendable, CustomStringConvertible {
    public let year: Int
    public let month: Int
    public let day: Int

    /// For values already known to be valid (derived from another valid day or a valid `ExpiryDate`).
    init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// Validates month and day-in-month like `ExpiryDate`, but accepts any year: a wrong phone clock
    /// (before 2000, after 2099) must never crash a status rule.
    public static func make(year: Int, month: Int, day: Int) throws(ExpiryDateError) -> CalendarDay {
        guard (1...12).contains(month) else { throw .invalidMonth(month) }
        guard (1...ExpiryDate.daysInMonth(year: year, month: month)).contains(day) else {
            throw .invalidDay(day, month: month, year: year)
        }
        return CalendarDay(year: year, month: month, day: day)
    }

    /// `"YYYY-MM-DD"` (four-digit year), as written in the shared fixtures; `nil` when malformed.
    public static func parse(_ iso: String) -> CalendarDay? {
        let parts = iso.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let year = asciiNumber(parts[0]), let month = asciiNumber(parts[1]), let day = asciiNumber(parts[2])
        else { return nil }
        return try? make(year: year, month: month, day: day)
    }

    /// ASCII digits only: no sign, no other script's digits (the Kotlin twin behaves the same).
    private static func asciiNumber(_ text: Substring) -> Int? {
        text.allSatisfy { ("0"..."9").contains($0) } ? Int(text) : nil
    }

    // MARK: Instant + zone → local day

    /// The calendar date of `instant` on a wall clock set to `timeZone` (Gregorian).
    public static func from(_ instant: Date, timeZone: TimeZone) -> CalendarDay {
        fromEpochDay(floorDiv(localSeconds(instant, timeZone), secondsPerDay))
    }

    /// Minutes elapsed since local midnight, wall clock (so 0…1439, even on a DST change day).
    public static func minutesSinceMidnight(of instant: Date, timeZone: TimeZone) -> Int {
        let seconds = localSeconds(instant, timeZone)
        return (seconds - floorDiv(seconds, secondsPerDay) * secondsPerDay) / 60
    }

    private static let secondsPerDay = 86_400
    /// About the year 5138: far beyond any real clock, small enough that `Int` never overflows.
    private static let maxSeconds = 100_000_000_000.0

    private static func localSeconds(_ instant: Date, _ timeZone: TimeZone) -> Int {
        let raw = instant.timeIntervalSince1970
        let clamped = raw.isFinite ? min(max(raw.rounded(.down), -maxSeconds), maxSeconds) : 0
        return Int(clamped) + timeZone.secondsFromGMT(for: instant)
    }

    // MARK: Day arithmetic (civil-calendar algorithm, identical in the Kotlin twin)

    /// Days since 1970-01-01 (negative before).
    public var epochDay: Int {
        let shifted = month <= 2 ? year - 1 : year
        let era = floorDiv(shifted, 400)
        let yearOfEra = shifted - era * 400
        let dayOfYear = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    public static func fromEpochDay(_ epochDay: Int) -> CalendarDay {
        let shifted = epochDay + 719_468
        let era = floorDiv(shifted, 146_097)
        let dayOfEra = shifted - era * 146_097
        let yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let monthIndex = (5 * dayOfYear + 2) / 153
        let day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
        let month = monthIndex < 10 ? monthIndex + 3 : monthIndex - 9
        let year = yearOfEra + era * 400 + (month <= 2 ? 1 : 0)
        return CalendarDay(year: year, month: month, day: day)
    }

    public func adding(days: Int) -> CalendarDay {
        Self.fromEpochDay(epochDay + days)
    }

    public func firstOfNextMonth() -> CalendarDay {
        month == 12
            ? CalendarDay(year: year + 1, month: 1, day: 1)
            : CalendarDay(year: year, month: month + 1, day: 1)
    }

    public static func < (lhs: CalendarDay, rhs: CalendarDay) -> Bool {
        lhs.epochDay < rhs.epochDay
    }

    /// `YYYY-MM-DD`.
    public var description: String {
        func pad(_ value: Int, _ width: Int) -> String {
            let text = String(value)
            return String(repeating: "0", count: max(0, width - text.count)) + text
        }
        return "\(pad(year, 4))-\(pad(month, 2))-\(pad(day, 2))"
    }
}

extension ExpiryDate {
    /// The last day the box may still be used, as a calendar day.
    public var lastValidCalendarDay: CalendarDay {
        CalendarDay(year: year, month: month, day: lastValidDay)
    }
}

/// Division rounding toward negative infinity, without branches.
private func floorDiv(_ dividend: Int, _ divisor: Int) -> Int {
    let remainder = ((dividend % divisor) + divisor) % divisor
    return (dividend - remainder) / divisor
}
