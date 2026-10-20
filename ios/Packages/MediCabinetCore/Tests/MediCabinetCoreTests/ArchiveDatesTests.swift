import Foundation
import Testing
@testable import MediCabinetCore

@Suite struct ArchiveDatesTests {
    @Test(arguments: [
        ("1970-01-01T00:00:00.000Z", Int64(0)),
        ("1970-01-01T00:00:00.001Z", 1),
        ("1969-12-31T23:59:59.999Z", -1),
        ("2026-10-19T08:15:30.123Z", 1_792_397_730_123),
        ("2000-02-29T12:00:00.000Z", 951_825_600_000),
        ("0000-01-01T00:00:00.000Z", -62_167_219_200_000),
        ("9999-12-31T23:59:59.999Z", 253_402_300_799_999),
        ("1900-03-01T00:00:00.000Z", -2_203_891_200_000)
    ])
    func knownInstantsParseAndFormatExactly(_ text: String, _ milliseconds: Int64) throws {
        let date = try #require(ArchiveDates.parse(text))
        #expect(ArchiveDates.milliseconds(of: date) == milliseconds)
        #expect(ArchiveDates.format(date) == text)
    }

    @Test(arguments: [
        "", "2026-10-19", "2026-10-19T08:15:30Z", "2026-10-19T08:15:30.123", "2026-10-19T08:15:30.123+02:00",
        "2026-10-19t08:15:30.123Z", "2026-10-19T08:15:30.123z", "2026-10-19 08:15:30.123Z",
        "2026/10/19T08:15:30.123Z", "2026-10-19T08-15-30.123Z", "2026-10-19T08:15:30,123Z",
        "2026-13-19T08:15:30.123Z", "2026-00-19T08:15:30.123Z", "2026-10-32T08:15:30.123Z",
        "2026-10-00T08:15:30.123Z", "2026-02-30T08:15:30.123Z", "2027-02-29T08:15:30.123Z",
        "1900-02-29T08:15:30.123Z", "2026-04-31T08:15:30.123Z",
        "2026-10-19T24:00:00.000Z", "2026-10-19T08:60:00.000Z", "2026-10-19T08:15:60.000Z",
        "2026-1a-19T08:15:30.123Z", "2026-10-19T08:15:30.12\u{0663}Z", "+026-10-19T08:15:30.123Z",
        " 2026-10-19T08:15:30.123Z", "2026-10-19T08:15:30.123Z "
    ])
    func textOutsideTheExactShapeIsRefused(_ text: String) {
        #expect(ArchiveDates.parse(text) == nil)
    }

    @Test func leapDaysFollowTheGregorianRule() {
        #expect(ArchiveDates.parse("2000-02-29T00:00:00.000Z") != nil)
        #expect(ArchiveDates.parse("2024-02-29T00:00:00.000Z") != nil)
        #expect(ArchiveDates.parse("1900-02-29T00:00:00.000Z") == nil)
        #expect(ArchiveDates.parse("2100-02-29T00:00:00.000Z") == nil)
        #expect(ArchiveDates.parse("2026-02-28T00:00:00.000Z") != nil)
        #expect(ArchiveDates.parse("2026-04-30T00:00:00.000Z") != nil)
        #expect(ArchiveDates.parse("2026-12-31T00:00:00.000Z") != nil)
    }

    @Test func everyDayOfARangeOfYearsRoundTrips() throws {
        var milliseconds: Int64 = ArchiveDates.minMilliseconds
        var last = ""
        while milliseconds < 4_102_444_800_000 {  // up to 2100-01-01, in steps of 37 days 11 minutes
            let date = ArchiveDates.date(milliseconds: milliseconds)
            let text = ArchiveDates.format(date)
            #expect(text > last || last.isEmpty)
            last = text
            let parsed = try #require(ArchiveDates.parse(text))
            #expect(ArchiveDates.milliseconds(of: parsed) == milliseconds)
            milliseconds += 37 * 86_400_000 + 11 * 60_000 + 7
        }
    }

    @Test func subMillisecondNoiseRoundsToTheNearestMillisecond() {
        let date = Date(timeIntervalSince1970: 1.0004999)
        #expect(ArchiveDates.milliseconds(of: date) == 1000)
        #expect(ArchiveDates.format(date) == "1970-01-01T00:00:01.000Z")
        #expect(ArchiveDates.milliseconds(of: Date(timeIntervalSince1970: 1.0005001)) == 1001)
    }

    @Test func datesOutsideTheFourDigitYearRangeAreNotRepresentable() {
        #expect(ArchiveDates.isRepresentable(ArchiveDates.date(milliseconds: ArchiveDates.minMilliseconds)))
        #expect(ArchiveDates.isRepresentable(ArchiveDates.date(milliseconds: ArchiveDates.maxMilliseconds)))
        #expect(!ArchiveDates.isRepresentable(ArchiveDates.date(milliseconds: ArchiveDates.minMilliseconds - 1)))
        #expect(!ArchiveDates.isRepresentable(ArchiveDates.date(milliseconds: ArchiveDates.maxMilliseconds + 1)))
        #expect(ArchiveDates.isRepresentable(Date.distantPast))
        #expect(ArchiveDates.isRepresentable(Date.distantFuture))
        #expect(!ArchiveDates.isRepresentable(Date(timeIntervalSince1970: -1e18)))
        #expect(!ArchiveDates.isRepresentable(Date(timeIntervalSince1970: 1e18)))
        #expect(!ArchiveDates.isRepresentable(Date(timeIntervalSince1970: -.infinity)))
        #expect(!ArchiveDates.isRepresentable(Date(timeIntervalSince1970: .infinity)))
    }
}
