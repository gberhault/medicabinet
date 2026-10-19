import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@Suite struct CategoryLabelTests {
    private func languageBundle(_ code: String) throws -> Bundle {
        let path = try #require(Bundle.main.path(forResource: code, ofType: "lproj"), "no \(code).lproj in the app")
        return try #require(Bundle(path: path))
    }

    @Test func keysWriteTheIdentifierWithUnderscores() {
        #expect(CategoryLabel.key(for: .painFever) == "category.pain_fever")
        #expect(CategoryLabel.key(for: .vitaminsMinerals) == "category.vitamins_minerals")
        #expect(CategoryLabel.key(for: .other) == "category.other")
    }

    @Test(arguments: ["fr", "en"])
    func everySharedCategoryHasALabelInEachLanguage(code: String) throws {
        let bundle = try languageBundle(code)
        for category in try SharedFixtures.categories() {
            let key = CategoryLabel.key(for: CategoryID(rawValue: category.name))
            let label = CategoryLabel.text(for: CategoryID(rawValue: category.name), bundle: bundle)
            #expect(label != key, "\(code): no translation for \(key)")
            #expect(!label.isEmpty)
        }
    }

    @Test func labelsAreTheOnesOfTheRequirements() throws {
        let french = try languageBundle("fr")
        let english = try languageBundle("en")
        #expect(CategoryLabel.text(for: .painFever, bundle: french) == "Douleur et fièvre")
        #expect(CategoryLabel.text(for: .coldENT, bundle: english) == "Nose, throat and ears")
        #expect(CategoryLabel.text(for: .firstAid, bundle: french) == "Trousse de secours")
        #expect(CategoryLabel.text(for: .uncategorized, bundle: english) == "Uncategorized")
    }

    @Test(arguments: ["fr", "en"])
    func anUnknownCategoryShowsTheOtherLabel(code: String) throws {
        let bundle = try languageBundle(code)
        let unknown = CategoryID(rawValue: "veterinary")
        #expect(CategoryLabel.text(for: unknown, bundle: bundle) == CategoryLabel.text(for: .other, bundle: bundle))
    }

    @Test func labelsNeverNameAnIndication() throws {
        // A guard on the product rule: categories tidy the cabinet, they do not say what to take for what.
        let forbidden = ["traite", "soigne", "contre ", "treat", "cure", "relief", "against "]
        for code in ["fr", "en"] {
            let bundle = try languageBundle(code)
            for id in Taxonomy.ordered {
                let label = CategoryLabel.text(for: id, bundle: bundle).lowercased()
                #expect(!forbidden.contains { label.contains($0) }, "\(code) label '\(label)' reads like an indication")
            }
        }
    }
}
