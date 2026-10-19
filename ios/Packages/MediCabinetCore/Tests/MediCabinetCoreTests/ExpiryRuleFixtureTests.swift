import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/expiry-status.json`, which the Android core also runs.
@Suite struct ExpiryRuleFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.expiryStatus()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 30)
        #expect(fixture.todayCases.count >= 10)
    }

    @Test(arguments: SharedFixtures.expiryStatusCases())
    func statusMatchesTheSharedExpectation(_ fixtureCase: ExpiryStatusCase) throws {
        let today = try #require(CalendarDay.parse(fixtureCase.today))
        let window = try #require(ExpiryWindow(rawValue: fixtureCase.windowDays))
        let expiry = try fixtureCase.expiry.map { try $0.expiryDate() }

        let assessment = ExpiryRule.assess(expiry, today: today, window: window)

        #expect(assessment.status.rawValue == fixtureCase.expect.status)
        #expect(assessment.daysLeft == fixtureCase.expect.daysLeft)
    }

    @Test(arguments: SharedFixtures.todayCases())
    func todayMatchesTheSharedExpectation(_ fixtureCase: TodayCase) throws {
        let instant = try #require(fixtureCase.date())
        let zone = try #require(TimeZone(identifier: fixtureCase.timeZone))

        let today = CalendarDay.from(instant, timeZone: zone)

        #expect(today.description == fixtureCase.expect)
        #expect(CalendarDay.minutesSinceMidnight(of: instant, timeZone: zone) == fixtureCase.minutesSinceMidnight)
    }
}
