import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of the repository-wide `fixtures/product-resolution.json`, which the Android core also runs.
@Suite struct ProductResolverFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() {
        #expect(SharedFixtures.productResolutionCases().count >= 14)
    }

    @Test(arguments: SharedFixtures.productResolutionCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: ProductResolutionCase) {
        let reference = fixtureCase.reference.map {
            ReferenceProduct(name: $0.name, category: $0.category.map { CategoryID(rawValue: $0) })
        }
        let named = fixtureCase.named.map {
            NamedProduct(
                gtin: "03400930000014", name: $0.name, category: CategoryID(rawValue: $0.category),
                categoryOverridden: $0.categoryOverridden, createdAt: Date(timeIntervalSince1970: 0)
            )
        }
        let previous = fixtureCase.previousBox.map {
            Box(
                product: ProductRef(gtin: "03400930000014", name: $0.name),
                category: CategoryID(rawValue: $0.category), categoryOverridden: $0.categoryOverridden,
                locationID: UUID(), createdAt: Date(timeIntervalSince1970: 0)
            )
        }
        let resolved = ProductResolver.resolve(reference: reference, named: named, previousBox: previous)
        #expect(resolved.name == fixtureCase.expect.name)
        #expect(resolved.category.rawValue == fixtureCase.expect.category)
        #expect(resolved.categoryOverridden == fixtureCase.expect.categoryOverridden)
    }
}
