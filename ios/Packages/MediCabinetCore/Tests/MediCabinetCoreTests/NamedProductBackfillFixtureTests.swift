import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/named-product-backfill.json`, which the Android core also runs.
@Suite struct NamedProductBackfillFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.namedProductBackfill()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 8)
    }

    @Test(arguments: SharedFixtures.namedProductBackfillCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: BackfillCase) {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let latest = fixtureCase.latest.map {
            Box(
                product: ProductRef(gtin: $0.gtin, name: $0.name), category: CategoryID(rawValue: $0.category),
                categoryOverridden: $0.categoryOverridden, locationID: UUID(), createdAt: Date(timeIntervalSince1970: 0)
            )
        }
        let known = Set(fixtureCase.known)
        let products = NamedProductBackfill.products(latest: latest, isKnown: { known.contains($0) }, ids: { UUID() }, now: now)
        #expect(products.map(\.gtin) == fixtureCase.expect.map(\.gtin))
        #expect(products.map(\.name) == fixtureCase.expect.map(\.name))
        #expect(products.map(\.category.rawValue) == fixtureCase.expect.map(\.category))
        #expect(products.map(\.categoryOverridden) == fixtureCase.expect.map(\.categoryOverridden))
        #expect(products.allSatisfy { $0.createdAt == now && $0.updatedAt == now })
        #expect(Set(products.map(\.id)).count == products.count)
    }
}
