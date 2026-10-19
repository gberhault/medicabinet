import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of the repository-wide `fixtures/expiry-dates.json`, which the Android core
/// also runs. A case added there runs here with no code change.
@Suite struct ExpiryDateFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.expiryDates()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 30)
    }

    @Test(arguments: SharedFixtures.expiryDateCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: ExpiryDateCase) {
        let expect = fixtureCase.expect
        do {
            let date = try ExpiryDate.make(year: fixtureCase.year, month: fixtureCase.month, day: fixtureCase.day)
            #expect(expect.error == nil, "expected error \(expect.error ?? "") but a date was built")
            #expect(date.lastValidDay == expect.lastValidDay)
            #expect(date.sortKey == expect.sortKey)
            #expect(date.isMonthPrecision == expect.monthPrecision)
        } catch {
            #expect(expect.error != nil, "unexpected error \(error)")
            #expect(error.kind == expect.error)
        }
    }

    @Test func errorKindsUseThePlatformNeutralNames() {
        #expect(ExpiryDateError.invalidYear(1).kind == "invalidYear")
        #expect(ExpiryDateError.invalidMonth(1).kind == "invalidMonth")
        #expect(ExpiryDateError.invalidDay(1, month: 1, year: 2027).kind == "invalidDay")
    }
}
