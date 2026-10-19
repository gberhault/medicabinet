import Testing
import MediCabinetCore

@Suite struct ExpiryRuleTests {
    @Test func storedWindowsMapToTheirChoice() {
        #expect(ExpiryWindow(storedDays: 30) == .days30)
        #expect(ExpiryWindow(storedDays: 60) == .days60)
        #expect(ExpiryWindow(storedDays: 90) == .days90)
    }

    @Test func missingOrUnknownStoredWindowsFallBackToTheDefault() {
        #expect(ExpiryWindow.default == .days60)
        #expect(ExpiryWindow(storedDays: nil) == .default)
        for bad in [0, 45, -30, 61, 365, Int.max] {
            #expect(ExpiryWindow(storedDays: bad) == .default)
        }
    }

    @Test func windowDaysMatchTheChoices() {
        #expect(ExpiryWindow.allCases.map(\.days) == [30, 60, 90])
    }

    @Test func expiresTodayOnlyOnTheExpiryDay() throws {
        let expiry = try ExpiryDate.make(year: 2027, month: 3, day: 15)
        func assess(_ day: Int) throws -> ExpiryAssessment {
            try ExpiryRule.assess(expiry, today: CalendarDay.make(year: 2027, month: 3, day: day), window: .days30)
        }
        #expect(try assess(15).expiresToday)
        #expect(try !assess(14).expiresToday)
        #expect(try !assess(16).expiresToday)
        let unknown = try ExpiryRule.assess(nil, today: CalendarDay.make(year: 2027, month: 3, day: 15), window: .days30)
        #expect(!unknown.expiresToday)
    }

    @Test func anUnknownExpiryIsNeverOk() throws {
        let today = try CalendarDay.make(year: 2027, month: 3, day: 15)
        for window in ExpiryWindow.allCases {
            let assessment = ExpiryRule.assess(nil, today: today, window: window)
            #expect(assessment.status == .unknown)
            #expect(assessment.daysLeft == nil)
        }
    }
}
