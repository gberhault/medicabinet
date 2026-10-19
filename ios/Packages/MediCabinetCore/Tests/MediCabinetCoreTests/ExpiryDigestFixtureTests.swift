import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/expiry-digest.json`, which the Android core also runs.
@Suite struct ExpiryDigestFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.expiryDigest()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 15)
        #expect(fixture.scheduleCases.count >= 10)
    }

    @Test(arguments: SharedFixtures.digestCountCases())
    func countsMatchTheSharedExpectation(_ fixtureCase: DigestCountCase) throws {
        let day = try #require(CalendarDay.parse(fixtureCase.digestDay))
        let window = try #require(ExpiryWindow(rawValue: fixtureCase.windowDays))
        let location = UUID()
        let boxes = try fixtureCase.boxes.map { try $0.box(location: location) }

        let counts = ExpiryDigest.counts(boxes: boxes, on: day, window: window)

        #expect(counts?.expired == fixtureCase.expect?.expired)
        #expect(counts?.expiringSoon == fixtureCase.expect?.expiringSoon)
        #expect((counts == nil) == (fixtureCase.expect == nil))
    }

    @Test(arguments: SharedFixtures.digestScheduleCases())
    func scheduleMatchesTheSharedExpectation(_ fixtureCase: DigestScheduleCase) throws {
        let today = try #require(CalendarDay.parse(fixtureCase.today))

        let upcoming = ExpiryDigest.upcoming(today: today, minutes: fixtureCase.minutesSinceMidnight, count: fixtureCase.count)
        let due = ExpiryDigest.due(today: today, minutes: fixtureCase.minutesSinceMidnight)

        #expect(upcoming.map(\.description) == fixtureCase.expect.upcoming)
        #expect(due.description == fixtureCase.expect.due)
    }

    @Test func namedConstantsMatchTheFixtureDescription() {
        #expect(ExpiryDigest.dayOfMonth == 1)
        #expect(ExpiryDigest.minutesSinceMidnight == 540)
        #expect(ExpiryDigest.scheduledAhead == 12)
    }

    @Test func aNegativeCountYieldsNoDigests() throws {
        let today = try CalendarDay.make(year: 2027, month: 3, day: 15)
        #expect(ExpiryDigest.upcoming(today: today, minutes: 0, count: -3).isEmpty)
    }
}
