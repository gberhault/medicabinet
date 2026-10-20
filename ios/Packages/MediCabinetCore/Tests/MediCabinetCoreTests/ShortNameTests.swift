import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/short-names.json`, which the Android core also runs.
@Suite struct ShortNameFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.shortNames()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 15)
    }

    @Test(arguments: SharedFixtures.shortNameCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: ShortNameCase) {
        // Compared by scalars: Swift's == treats canonically equivalent strings as equal.
        #expect(Array(ShortName.of(fixtureCase.input).unicodeScalars) == Array(fixtureCase.expect.unicodeScalars))
    }
}

@Suite struct ShortNameTests {
    @Test func shorteningTwiceChangesNothing() {
        for name in ["DOLIPRANE 1000 mg, comprimé", "A,B", ", x", "  "] {
            let once = ShortName.of(name)
            #expect(ShortName.of(once) == once)
        }
    }
}
