import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of the repository-wide `fixtures/gs1-datamatrix.json`, which the Android core
/// also runs. A case added there runs here with no code change.
@Suite struct GS1ParserFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.gs1Datamatrix()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 80)
    }

    @Test(arguments: SharedFixtures.gs1Cases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: GS1Case) {
        let expect = fixtureCase.expect
        #expect(GS1Parser.shape(of: fixtureCase.payload) == fixtureCase.shape)
        do {
            let code = try GS1Parser.parse(fixtureCase.payload)
            #expect(expect.error == nil, "expected error \(expect.error ?? "") but a code was parsed")
            #expect(code.gtin == expect.gtin)
            #expect(code.symbology.rawValue == expect.symbology)
            #expect(code.lot == expect.lot)
            #expect(code.serial == expect.serial)
            switch (code.expiry, expect.expiry) {
            case (.missing, .missing?):
                break
            case let (.date(date), .date(year, month, day)?):
                #expect(date.year == year && date.month == month && date.day == day)
            case let (.invalid(kind), .invalid(expected)?):
                #expect(kind == expected)
            default:
                Issue.record("expiry \(code.expiry) does not match \(String(describing: expect.expiry))")
            }
        } catch {
            #expect(expect.error != nil, "unexpected error \(error)")
            #expect(error.kind == expect.error)
        }
    }

    @Test func aShapeNeverContainsALotOrSerialValue() {
        for fixtureCase in SharedFixtures.gs1Cases() {
            guard let code = try? GS1Parser.parse(fixtureCase.payload) else { continue }
            for value in [code.lot, code.serial].compactMap({ $0 }) where value.count > 3 {
                #expect(!fixtureCase.shape.contains(value), "\(fixtureCase.name)")
            }
        }
    }
}
