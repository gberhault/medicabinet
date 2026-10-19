public enum ExpiryDateError: Error, Equatable, Sendable {
    case invalidYear(Int)
    case invalidMonth(Int)
    case invalidDay(Int, month: Int, year: Int)

    /// Platform-neutral name, shared with the Android app and `fixtures/expiry-dates.json`.
    public var kind: String {
        switch self {
        case .invalidYear: "invalidYear"
        case .invalidMonth: "invalidMonth"
        case .invalidDay: "invalidDay"
        }
    }
}

/// The expiry printed on a box: day-precise, or month-precise when the day is unknown
/// (GS1 `DD=00` means "end of that month"). Built only through `make`, so an invalid date
/// cannot exist — invalid input is rejected, never clamped or guessed.
public struct ExpiryDate: Hashable, Sendable, Comparable {
    public static let supportedYears = 2000...2099

    public let year: Int
    public let month: Int
    /// `nil` means month precision.
    public let day: Int?

    private init(year: Int, month: Int, day: Int?) {
        self.year = year
        self.month = month
        self.day = day
    }

    public static func make(year: Int, month: Int, day: Int?) throws(ExpiryDateError) -> ExpiryDate {
        guard supportedYears.contains(year) else { throw .invalidYear(year) }
        guard (1...12).contains(month) else { throw .invalidMonth(month) }
        if let day {
            guard (1...daysInMonth(year: year, month: month)).contains(day) else {
                throw .invalidDay(day, month: month, year: year)
            }
        }
        return ExpiryDate(year: year, month: month, day: day)
    }

    public var isMonthPrecision: Bool { day == nil }

    /// The last day on which the box may still be used: its printed day, or the last day of
    /// the month for month precision.
    public var lastValidDay: Int {
        day ?? Self.daysInMonth(year: year, month: month)
    }

    /// `YYYY-MM-DD` of `lastValidDay`; orders dates correctly as text (used as a DB sort key).
    public var sortKey: String {
        func pad(_ value: Int) -> String { value < 10 ? "0\(value)" : "\(value)" }
        return "\(year)-\(pad(month))-\(pad(lastValidDay))"
    }

    public static func < (lhs: ExpiryDate, rhs: ExpiryDate) -> Bool {
        lhs.sortKey < rhs.sortKey
    }

    static func daysInMonth(year: Int, month: Int) -> Int {
        switch month {
        case 2: isLeap(year) ? 29 : 28
        case 4, 6, 9, 11: 30
        default: 31
        }
    }

    private static func isLeap(_ year: Int) -> Bool {
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }
}
