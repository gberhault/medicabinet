import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/location-names.json`, which the Android core also runs.
@Suite struct LocationNameFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.locationNames()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 15)
    }

    @Test(arguments: SharedFixtures.locationNameCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: LocationNameCase) {
        do {
            let name = try LocationName.validate(
                fixtureCase.input, existing: fixtureCase.existing, renaming: fixtureCase.renaming)
            #expect(fixtureCase.expect.error == nil, "expected error \(fixtureCase.expect.error ?? "")")
            #expect(name == fixtureCase.expect.name)
        } catch {
            #expect(fixtureCase.expect.error != nil, "unexpected error \(error)")
            #expect(error.kind == fixtureCase.expect.error)
        }
    }

    @Test func errorKindsUseThePlatformNeutralNames() {
        #expect(LocationNameError.empty.kind == "empty")
        #expect(LocationNameError.tooLong.kind == "tooLong")
        #expect(LocationNameError.duplicateName.kind == "duplicateName")
    }

    @Test func theLimitFitsTheDatabaseCheck() {
        // v1: location.name CHECK (length(name) BETWEEN 1 AND 100), counted in code points.
        #expect(LocationName.maxLength <= 100)
    }
}
