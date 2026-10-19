import Testing
@testable import MediCabinetCore

@Suite struct ExpiryDateTests {
    @Test func dayPrecisionKeepsItsDay() throws {
        let date = try ExpiryDate.make(year: 2027, month: 3, day: 15)
        #expect(date.year == 2027)
        #expect(date.month == 3)
        #expect(date.day == 15)
        #expect(!date.isMonthPrecision)
        #expect(date.lastValidDay == 15)
    }

    @Test func monthPrecisionHasNoDayAndEndsOnLastDayOfMonth() throws {
        let date = try ExpiryDate.make(year: 2027, month: 4, day: nil)
        #expect(date.day == nil)
        #expect(date.isMonthPrecision)
        #expect(date.lastValidDay == 30)
    }

    @Test(arguments: [
        (1, 31), (2, 28), (3, 31), (4, 30), (5, 31), (6, 30),
        (7, 31), (8, 31), (9, 30), (10, 31), (11, 30), (12, 31)
    ])
    func monthLengthsInCommonYear(month: Int, expected: Int) throws {
        #expect(try ExpiryDate.make(year: 2027, month: month, day: nil).lastValidDay == expected)
    }

    @Test func leapYearFebruaryHas29Days() throws {
        #expect(try ExpiryDate.make(year: 2028, month: 2, day: nil).lastValidDay == 29)
        _ = try ExpiryDate.make(year: 2028, month: 2, day: 29)
    }

    @Test func year2000IsALeapYear() throws {
        #expect(try ExpiryDate.make(year: 2000, month: 2, day: nil).lastValidDay == 29)
    }

    @Test func feb29InACommonYearIsRejected() {
        #expect(throws: ExpiryDateError.invalidDay(29, month: 2, year: 2027)) {
            try ExpiryDate.make(year: 2027, month: 2, day: 29)
        }
    }

    @Test(arguments: [0, 13, -1, 99])
    func invalidMonthIsRejected(month: Int) {
        #expect(throws: ExpiryDateError.invalidMonth(month)) {
            try ExpiryDate.make(year: 2027, month: month, day: nil)
        }
    }

    @Test(arguments: [0, 32, -3])
    func invalidDayIsRejected(day: Int) {
        #expect(throws: ExpiryDateError.invalidDay(day, month: 1, year: 2027)) {
            try ExpiryDate.make(year: 2027, month: 1, day: day)
        }
    }

    @Test func april31IsRejected() {
        #expect(throws: ExpiryDateError.invalidDay(31, month: 4, year: 2027)) {
            try ExpiryDate.make(year: 2027, month: 4, day: 31)
        }
    }

    @Test(arguments: [1999, 2100, 0, -5, 27])
    func yearOutsideSupportedRangeIsRejected(year: Int) {
        #expect(throws: ExpiryDateError.invalidYear(year)) {
            try ExpiryDate.make(year: year, month: 6, day: nil)
        }
    }

    @Test func yearBoundsAreInclusive() throws {
        _ = try ExpiryDate.make(year: 2000, month: 1, day: 1)
        _ = try ExpiryDate.make(year: 2099, month: 12, day: 31)
    }

    @Test func sortKeyUsesLastValidDayAndIsZeroPadded() throws {
        #expect(try ExpiryDate.make(year: 2027, month: 3, day: 5).sortKey == "2027-03-05")
        #expect(try ExpiryDate.make(year: 2028, month: 2, day: nil).sortKey == "2028-02-29")
    }

    @Test func monthPrecisionSortsAfterEarlierDaysOfSameMonth() throws {
        let mid = try ExpiryDate.make(year: 2027, month: 6, day: 10)
        let end = try ExpiryDate.make(year: 2027, month: 6, day: nil)
        #expect(mid < end)
    }

    @Test func dayAndMonthPrecisionAreDistinctValues() throws {
        let day = try ExpiryDate.make(year: 2027, month: 6, day: 30)
        let month = try ExpiryDate.make(year: 2027, month: 6, day: nil)
        #expect(day != month)
        #expect(day.sortKey == month.sortKey)
    }
}
