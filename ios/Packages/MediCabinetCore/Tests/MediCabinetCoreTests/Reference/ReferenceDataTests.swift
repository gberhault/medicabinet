import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

@Suite struct ReferenceDataDateTests {
    @Test func parsesARealCalendarDay() throws {
        let date = try #require(ReferenceDataDate(iso: "2026-10-18"))
        #expect((date.year, date.month, date.day) == (2026, 10, 18))
    }

    @Test func leapDays() {
        #expect(ReferenceDataDate(iso: "2028-02-29") != nil)
        #expect(ReferenceDataDate(iso: "2027-02-29") == nil)
    }

    @Test(arguments: [
        "", "2026-13-01", "2026-00-10", "2026-04-31", "2026-4-1", "20261018", "2026-10-18 ", " 2026-10-18",
        "2026/10/18", "2026-10-1a", "1999-12-31", "2100-01-01", "２０２６-10-18", "2026-10-18-01"
    ])
    func rejectsAnythingElse(_ text: String) {
        #expect(ReferenceDataDate(iso: text) == nil)
    }
}

@Suite struct ReferenceDataSourceTests {
    private let date = ReferenceDataDate(iso: "2026-10-18")!  // swiftlint:disable:this force_unwrapping
    private let product = ReferenceProduct(name: "TESTAMOL 500 mg, comprimé", category: .painFever)

    @Test func rawValuesAreThePlatformNeutralNames() {
        #expect(ReferenceUnavailableReason.missing.rawValue == "missing")
        #expect(ReferenceUnavailableReason.unreadable.rawValue == "unreadable")
        #expect(ReferenceUnavailableReason.unsupportedSchema.rawValue == "unsupportedSchema")
    }

    @Test func aProductIsFoundByItsDataMatrixGTIN() async throws {
        let source = InMemoryReferenceData(products: ["3400900000013": product], dataDate: date)
        #expect(try await source.product(gtin: "03400900000013") == product)
        #expect(await source.status() == .available(dataDate: date))
    }

    @Test func anUnknownOrMalformedGTINIsUnknown() async throws {
        let source = InMemoryReferenceData(products: ["3400900000013": product], dataDate: date)
        #expect(try await source.product(gtin: "03400900005001") == nil)
        #expect(try await source.product(gtin: "3400900000013") == nil)
        #expect(try await source.product(gtin: "") == nil)
    }

    @Test func anUnavailableSourceKnowsNothingAndSaysWhy() async throws {
        let source = InMemoryReferenceData(products: ["3400900000013": product], status: .unavailable(.unreadable))
        #expect(try await source.product(gtin: "03400900000013") == nil)
        #expect(await source.status() == .unavailable(.unreadable))
    }

    @Test func aReferenceProductDefaultsToNoCategory() {
        #expect(ReferenceProduct(name: "X").category == nil)
    }
}
