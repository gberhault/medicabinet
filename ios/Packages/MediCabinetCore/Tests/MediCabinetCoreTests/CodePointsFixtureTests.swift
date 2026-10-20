import Testing
import MediCabinetCoreTestSupport
@testable import MediCabinetCore

/// Runs every case of `fixtures/archive-code-points.json`, which the Android core also runs.
@Suite struct CodePointsFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.archiveCodePoints()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 10)
    }

    @Test(arguments: SharedFixtures.archiveCodePointCases())
    func lengthIsCountedInCodePoints(_ fixtureCase: ArchiveCodePointCase) {
        #expect(codePointCount(fixtureCase.text) == fixtureCase.codePoints)
    }
}
