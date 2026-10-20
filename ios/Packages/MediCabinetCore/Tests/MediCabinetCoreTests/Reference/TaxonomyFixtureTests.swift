import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// `Taxonomy` against the repository-wide `fixtures/categories.json`, which the Android core and the reference-data
/// build also use.
@Suite struct TaxonomyFixtureTests {
    @Test func theSharedFixtureLoadsAndHasTwelveCategories() throws {
        #expect(try SharedFixtures.categories().count == 12)
    }

    @Test func orderedMatchesTheFixtureInOrder() throws {
        let fixture = try SharedFixtures.categories().map(\.name)
        #expect(Taxonomy.ordered.map(\.rawValue) == fixture)
    }

    @Test func userSelectableMatchesTheFixture() throws {
        let fixture = try SharedFixtures.categories().filter(\.userSelectable).map(\.name)
        #expect(Taxonomy.userSelectable.map(\.rawValue) == fixture)
        #expect(!Taxonomy.userSelectable.contains(.uncategorized))
    }

    @Test func firstAidIsSelectableButNeverAssignedByTheBuild() throws {
        let firstAid = try #require(try SharedFixtures.categories().first { $0.name == "first-aid" })
        #expect(firstAid.userSelectable)
        #expect(!firstAid.assignedByBuild)
    }

    @Test func uncategorizedMatchesTheModelConstant() {
        #expect(CategoryID.uncategorized.rawValue == "uncategorized")
        #expect(Taxonomy.ordered.last == .uncategorized)
    }

    @Test func everyKnownCategoryIsDisplayedAsItself() {
        for id in Taxonomy.ordered {
            #expect(Taxonomy.displayed(id) == id)
        }
    }

    @Test func anUnknownCategoryIsDisplayedAsOtherWithoutChangingTheStoredValue() {
        let fromANewerApp = CategoryID(rawValue: "veterinary")
        #expect(Taxonomy.displayed(fromANewerApp) == .other)
        #expect(fromANewerApp.rawValue == "veterinary")
        #expect(Taxonomy.displayed(CategoryID(rawValue: "")) == .other)
    }
}
