import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/category-order.json`, which the Android core also runs.
@Suite struct CategoryOrderFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.categoryOrder()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 8)
    }

    @Test(arguments: SharedFixtures.categoryOrderCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: CategoryOrderCase) {
        let labels = Dictionary(uniqueKeysWithValues: fixtureCase.labels.map { (CategoryID(rawValue: $0.key), $0.value) })
        #expect(CategoryOrder.alphabetical(labels: labels).map(\.rawValue) == fixtureCase.expect)
    }
}

@Suite struct CategoryOrderTests {
    @Test func everyShippedCategoryIsOrderedExactlyOnce() {
        let labels = Dictionary(uniqueKeysWithValues: Taxonomy.ordered.map { ($0, $0.rawValue) })
        let order = CategoryOrder.alphabetical(labels: labels)
        #expect(Set(order) == Set(Taxonomy.ordered))
        #expect(order.count == Taxonomy.ordered.count)
    }
}
