import Foundation

/// Archive timestamps: `yyyy-MM-ddTHH:mm:ss.SSSZ`, UTC, exactly 24 characters, millisecond
/// precision (the database's precision, so a round trip is exact). Done by hand so both
/// platforms accept and write exactly the same text, with no locale or calendar involved.
enum ArchiveDates {
    /// Milliseconds since 1970-01-01T00:00:00Z for 0000-01-01T00:00:00.000Z and 9999-12-31T23:59:59.999Z.
    static let minMilliseconds: Int64 = -62_167_219_200_000
    static let maxMilliseconds: Int64 = 253_402_300_799_999

    static func milliseconds(of date: Date) -> Int64 {
        let value = (date.timeIntervalSince1970 * 1000).rounded()
        // Beyond the representable range the text could not be formed anyway; the validator
        // refuses those dates, so clamp instead of trapping on the conversion.
        if value < Double(minMilliseconds) { return minMilliseconds - 1 }
        if value > Double(maxMilliseconds) { return maxMilliseconds + 1 }
        return Int64(value)
    }

    static func date(milliseconds: Int64) -> Date {
        Date(timeIntervalSince1970: Double(milliseconds) / 1000)
    }

    static func isRepresentable(_ date: Date) -> Bool {
        (minMilliseconds...maxMilliseconds).contains(milliseconds(of: date))
    }

    /// The text for a representable date (callers check `isRepresentable` first).
    static func format(_ date: Date) -> String {
        let total = milliseconds(of: date)
        let days = floorDiv(total, 86_400_000)
        let millisOfDay = total - days * 86_400_000
        let calendarDate = civil(fromDays: days)
        let hour = millisOfDay / 3_600_000
        let minute = millisOfDay / 60_000 % 60
        let second = millisOfDay / 1000 % 60
        let milli = millisOfDay % 1000
        return pad(calendarDate.year, 4) + "-" + pad(Int64(calendarDate.month), 2) + "-" + pad(Int64(calendarDate.day), 2) + "T"
            + pad(hour, 2) + ":" + pad(minute, 2) + ":" + pad(second, 2) + "." + pad(milli, 3) + "Z"
    }

    /// `nil` unless `text` has exactly the shape above with every field in range.
    static func parse(_ text: String) -> Date? {
        let bytes = Array(text.utf8)
        guard bytes.count == 24 else { return nil }
        let separators: [(Int, UInt8)] = [(4, 0x2D), (7, 0x2D), (10, 0x54), (13, 0x3A), (16, 0x3A), (19, 0x2E), (23, 0x5A)]
        for (position, expected) in separators where bytes[position] != expected { return nil }
        func number(_ range: Range<Int>) -> Int64? {
            var value: Int64 = 0
            for byte in bytes[range] {
                guard (0x30...0x39).contains(byte) else { return nil }
                value = value * 10 + Int64(byte - 0x30)
            }
            return value
        }
        guard let year = number(0..<4), let month = number(5..<7), let day = number(8..<10),
              let hour = number(11..<13), let minute = number(14..<16), let second = number(17..<19),
              let milli = number(20..<23)
        else { return nil }
        guard (1...12).contains(month), hour < 24, minute < 60, second < 60 else { return nil }
        guard (1...Int64(daysInMonth(year: year, month: month))).contains(day) else { return nil }
        let dayCount = days(fromCivil: year, month, day)
        return date(milliseconds: dayCount * 86_400_000 + hour * 3_600_000 + minute * 60_000 + second * 1000 + milli)
    }

    private static func daysInMonth(year: Int64, month: Int64) -> Int {
        switch month {
        case 2: isLeap(year) ? 29 : 28
        case 4, 6, 9, 11: 30
        default: 31
        }
    }

    private static func isLeap(_ year: Int64) -> Bool {
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }

    private static func pad(_ value: Int64, _ width: Int) -> String {
        let digits = String(value)
        return String(repeating: "0", count: max(width - digits.count, 0)) + digits
    }

    private static func floorDiv(_ dividend: Int64, _ divisor: Int64) -> Int64 {
        let quotient = dividend / divisor
        return (dividend % divisor != 0 && (dividend < 0) != (divisor < 0)) ? quotient - 1 : quotient
    }

    /// Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's `days_from_civil`).
    private static func days(fromCivil year: Int64, _ month: Int64, _ day: Int64) -> Int64 {
        let marchBasedYear = month <= 2 ? year - 1 : year
        let era = floorDiv(marchBasedYear, 400)
        let yearOfEra = marchBasedYear - era * 400
        let marchBasedMonth = month > 2 ? month - 3 : month + 9
        let dayOfYear = (153 * marchBasedMonth + 2) / 5 + day - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    private struct CivilDate {
        let year: Int64
        let month: Int
        let day: Int
    }

    /// The inverse (`civil_from_days`).
    private static func civil(fromDays days: Int64) -> CivilDate {
        let shiftedDays = days + 719_468
        let era = floorDiv(shiftedDays, 146_097)
        let dayOfEra = shiftedDays - era * 146_097
        let yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let marchBasedMonth = (5 * dayOfYear + 2) / 153
        let day = Int(dayOfYear - (153 * marchBasedMonth + 2) / 5 + 1)
        let month = Int(marchBasedMonth < 10 ? marchBasedMonth + 3 : marchBasedMonth - 9)
        let year = yearOfEra + era * 400 + (month <= 2 ? 1 : 0)
        return CivilDate(year: year, month: month, day: day)
    }
}
