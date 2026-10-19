import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// `CIP13.from(gtin:)` against `fixtures/reference-lookup.json`, which the Android core runs too.
@Suite struct CIP13FixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        #expect(try SharedFixtures.referenceLookup().count >= 16)
    }

    @Test(arguments: SharedFixtures.referenceLookupCases())
    func conversionMatchesTheSharedExpectation(_ lookupCase: ReferenceLookupCase) {
        #expect(CIP13.from(gtin: lookupCase.gtin) == lookupCase.expect.cip13)
    }

    @Test func aDataMatrixGTINDropsItsLeadingZero() {
        #expect(CIP13.from(gtin: "03400935955838") == "3400935955838")
    }

    @Test func aWrongCheckDigitIsRefused() {
        #expect(CIP13.from(gtin: "03400935955839") == nil)
    }

    @Test func onlyThe3400PrefixIsAMedicine() {
        #expect(CIP13.from(gtin: "03401935955831") == nil)
        #expect(CIP13.from(gtin: "13400935955835") == nil)
    }

    @Test func nothingIsTrimmedOrCorrected() {
        #expect(CIP13.from(gtin: " 03400935955838") == nil)
        #expect(CIP13.from(gtin: "03400935955838\n") == nil)
        #expect(CIP13.from(gtin: "3400935955838") == nil)
    }
}
