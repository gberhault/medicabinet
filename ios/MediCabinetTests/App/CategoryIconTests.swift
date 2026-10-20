import MediCabinetCore
import Testing
import UIKit
@testable import MediCabinet

@Suite struct CategoryIconTests {
    @Test func everyTaxonomyCategoryHasADistinctSymbolThatExists() {
        var seen: [String: CategoryID] = [:]
        for id in Taxonomy.ordered {
            let symbol = CategoryIcon.symbol(for: id)
            #expect(UIImage(systemName: symbol) != nil, "\(id.rawValue): no SF Symbol named \(symbol)")
            #expect(seen[symbol] == nil, "\(id.rawValue) shares \(symbol) with \(seen[symbol]?.rawValue ?? "?")")
            seen[symbol] = id
        }
    }

    @Test func anUnknownCategoryGetsTheOtherSymbol() {
        #expect(CategoryIcon.symbol(for: CategoryID(rawValue: "veterinary")) == CategoryIcon.symbol(for: .other))
    }
}
