import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/expiring-overview.json`, which the Android core also runs.
@Suite struct ExpiringOverviewFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.expiringOverview()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 9)
    }

    @Test(arguments: SharedFixtures.overviewCases())
    func overviewMatchesTheSharedExpectation(_ fixtureCase: OverviewCase) throws {
        let today = try #require(CalendarDay.parse(fixtureCase.today))
        let window = try #require(ExpiryWindow(rawValue: fixtureCase.windowDays))
        let location = UUID()
        let boxes = try fixtureCase.boxes.map { try $0.box(location: location) }

        let overview = ExpiringOverview.make(boxes: boxes, today: today, window: window)

        func ids(_ entries: [ExpiringEntry]) -> [String] { entries.map { $0.box.id.uuidString.lowercased() } }
        #expect(ids(overview.expired) == fixtureCase.expect.expired)
        #expect(ids(overview.expiringSoon) == fixtureCase.expect.expiringSoon)
        #expect(ids(overview.unknown) == fixtureCase.expect.unknown)
        #expect(overview.attentionCount == fixtureCase.expect.badge)
        #expect(overview.isEmpty == fixtureCase.expect.isEmpty)
    }
}
