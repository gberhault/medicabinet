import Foundation
import Testing
import MediCabinetCore

@Suite struct CalendarDayTests {
    private func day(_ year: Int, _ month: Int, _ day: Int) throws -> CalendarDay {
        try CalendarDay.make(year: year, month: month, day: day)
    }

    @Test func theEpochIsDayZero() throws {
        #expect(try day(1970, 1, 1).epochDay == 0)
        #expect(try day(1970, 1, 2).epochDay == 1)
        #expect(try day(1969, 12, 31).epochDay == -1)
        #expect(try day(2000, 3, 1).epochDay == 11_017)
    }

    @Test func everyDayOfTheSupportedYearsRoundTripsAndIncrementsByOne() throws {
        var previous = try day(2000, 1, 1)
        #expect(CalendarDay.fromEpochDay(previous.epochDay) == previous)
        while previous.year < 2100 {
            let next = previous.adding(days: 1)
            #expect(next.epochDay == previous.epochDay + 1)
            #expect(CalendarDay.fromEpochDay(next.epochDay) == next)
            #expect(next > previous)
            previous = next
        }
        #expect(previous == (try day(2100, 1, 1)))
    }

    @Test func wideYearsRoundTripToo() throws {
        for year in [1, 1600, 1900, 1969, 2100, 2400, 3000] {
            let value = try day(year, 2, 28)
            #expect(CalendarDay.fromEpochDay(value.epochDay) == value)
        }
    }

    @Test func addingDaysCrossesMonthsYearsAndLeapDays() throws {
        #expect(try day(2027, 1, 31).adding(days: 1) == day(2027, 2, 1))
        #expect(try day(2028, 2, 28).adding(days: 1) == day(2028, 2, 29))
        #expect(try day(2027, 2, 28).adding(days: 1) == day(2027, 3, 1))
        #expect(try day(2027, 12, 31).adding(days: 1) == day(2028, 1, 1))
        #expect(try day(2027, 3, 1).adding(days: -1) == day(2027, 2, 28))
        #expect(try day(2027, 3, 15).adding(days: 0) == day(2027, 3, 15))
    }

    @Test func firstOfNextMonthRollsOverTheYear() throws {
        #expect(try day(2027, 3, 15).firstOfNextMonth() == day(2027, 4, 1))
        #expect(try day(2027, 3, 31).firstOfNextMonth() == day(2027, 4, 1))
        #expect(try day(2027, 12, 31).firstOfNextMonth() == day(2028, 1, 1))
    }

    @Test func makeValidatesMonthAndDayButAcceptsAnyYear() throws {
        #expect(throws: ExpiryDateError.invalidMonth(13)) { try CalendarDay.make(year: 2027, month: 13, day: 1) }
        #expect(throws: ExpiryDateError.invalidMonth(0)) { try CalendarDay.make(year: 2027, month: 0, day: 1) }
        #expect(throws: ExpiryDateError.invalidDay(31, month: 4, year: 2027)) {
            try CalendarDay.make(year: 2027, month: 4, day: 31)
        }
        #expect(throws: ExpiryDateError.invalidDay(29, month: 2, year: 2027)) {
            try CalendarDay.make(year: 2027, month: 2, day: 29)
        }
        #expect(throws: ExpiryDateError.invalidDay(0, month: 2, year: 2027)) {
            try CalendarDay.make(year: 2027, month: 2, day: 0)
        }
        #expect(try day(2028, 2, 29).day == 29)
        #expect(try day(1999, 12, 31).year == 1999)
        #expect(try day(2100, 1, 1).year == 2100)
    }

    @Test func parseReadsIsoDatesAndRejectsEverythingElse() throws {
        #expect(CalendarDay.parse("2027-03-15") == (try day(2027, 3, 15)))
        let malformed = ["", "2027-3-15", "27-03-15", "2027-03-5", "2027/03/15", "2027-13-01", "2027-02-30"]
        for bad in malformed + ["abcd-ef-gh", "2027-03", "2027-03-15-01", "2027-+3-15", "٢٠٢٧-03-15"] {
            #expect(CalendarDay.parse(bad) == nil, "\(bad) should not parse")
        }
    }

    @Test func descriptionIsZeroPaddedIso() throws {
        #expect(try day(2027, 3, 5).description == "2027-03-05")
        #expect(try day(999, 11, 20).description == "0999-11-20")
    }

    @Test func expiryDateExposesItsLastValidCalendarDay() throws {
        #expect(try ExpiryDate.make(year: 2027, month: 4, day: nil).lastValidCalendarDay == day(2027, 4, 30))
        #expect(try ExpiryDate.make(year: 2028, month: 2, day: nil).lastValidCalendarDay == day(2028, 2, 29))
        #expect(try ExpiryDate.make(year: 2027, month: 4, day: 7).lastValidCalendarDay == day(2027, 4, 7))
    }

    @Test func fromInstantUsesTheZoneWallClock() throws {
        let instant = Date(timeIntervalSince1970: 1_806_535_800) // 2027-03-31T23:30:00Z
        let paris = try #require(TimeZone(identifier: "Europe/Paris"))
        let utc = try #require(TimeZone(identifier: "UTC"))
        #expect(CalendarDay.from(instant, timeZone: utc).description == "2027-03-31")
        #expect(CalendarDay.from(instant, timeZone: paris).description == "2027-04-01")
        #expect(CalendarDay.minutesSinceMidnight(of: instant, timeZone: paris) == 90)
    }

    @Test func instantsBeforeTheEpochAndAbsurdClocksNeverCrash() throws {
        let utc = try #require(TimeZone(identifier: "UTC"))
        let before = Date(timeIntervalSince1970: -1) // 1969-12-31T23:59:59Z
        #expect(CalendarDay.from(before, timeZone: utc).description == "1969-12-31")
        #expect(CalendarDay.minutesSinceMidnight(of: before, timeZone: utc) == 1_439)
        _ = CalendarDay.from(Date.distantFuture, timeZone: utc)
        _ = CalendarDay.from(Date.distantPast, timeZone: utc)
        #expect(CalendarDay.from(Date(timeIntervalSince1970: .nan), timeZone: utc).description == "1970-01-01")
    }
}
