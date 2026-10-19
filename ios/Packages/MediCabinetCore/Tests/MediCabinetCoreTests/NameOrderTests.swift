import Foundation
import Testing
import MediCabinetCore

@Suite struct NameOrderTests {
    private func box(_ name: String, id: String = "00000000-0000-0000-0000-000000000001") throws -> Box {
        Box(
            id: try #require(UUID(uuidString: id)),
            product: ProductRef(name: name),
            locationID: UUID(),
            createdAt: Date(timeIntervalSince1970: 0)
        )
    }

    @Test func foldingLowersCaseAndRemovesAccents() {
        #expect(NameOrder.folded("Éfferalgan") == "efferalgan")
        #expect(NameOrder.folded("ÉFFERALGAN") == "efferalgan")
        #expect(NameOrder.folded("Crème à l’œuf") == "creme a l’œuf")
        #expect(NameOrder.folded("") == "")
    }

    @Test func foldingDecomposesPrecomposedAndCombiningFormsAlike() {
        #expect(NameOrder.folded("\u{00E9}") == NameOrder.folded("e\u{0301}"))
    }

    @Test func accentsAndCaseDoNotSeparateNames() throws {
        #expect(try NameOrder.precedes(box("Éfferalgan"), box("Zyrtec")))
        #expect(try !NameOrder.precedes(box("Zyrtec"), box("éfferalgan")))
        #expect(try NameOrder.precedes(box("advil"), box("Éfferalgan")))
    }

    @Test func equalFoldedNamesBreakByLowerCaseId() throws {
        let low = try box("Doliprane", id: "00000000-0000-0000-0000-00000000000a")
        let high = try box("doliprane", id: "00000000-0000-0000-0000-00000000000b")
        #expect(NameOrder.precedes(low, high))
        #expect(!NameOrder.precedes(high, low))
    }

    @Test func aBoxDoesNotPrecedeItself() throws {
        let value = try box("Doliprane")
        #expect(!NameOrder.precedes(value, value))
    }

    @Test func ordersByScalarValueNotLocale() throws {
        // Digits sort before letters, and a space before both.
        #expect(try NameOrder.precedes(box("2 Doliprane"), box("Advil")))
        #expect(try NameOrder.precedes(box("A B"), box("AB")))
    }
}
