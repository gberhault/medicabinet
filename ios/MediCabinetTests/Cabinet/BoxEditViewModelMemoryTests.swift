import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

/// A box edit remembers the new name or category of a code the reference does not know, and nothing else (#57).
@MainActor
@Suite struct BoxEditViewModelMemoryTests {
    private func editor(
        _ kit: CabinetTestKit, box: Box, lookup: StubLookup = StubLookup(), named: (any NamedProductRepository)? = nil
    ) async throws -> BoxEditViewModel {
        BoxEditViewModel(
            box: box, locations: try await kit.locations.all(), boxes: kit.boxes, lookup: lookup,
            memory: ProductMemory(named: named ?? kit.named, lookup: lookup)
        )
    }

    @Test func aNewNameIsRemembered() async throws {
        let kit = try await CabinetTestKit()
        let model = try await editor(kit, box: try await kit.add("Test", expiryInDays: 100))
        model.name = "Crème solaire"
        #expect(await model.save())
        #expect(try await kit.named.namedProduct(gtin: CabinetTestKit.gtin)?.name == "Crème solaire")
    }

    @Test func aChosenCategoryAndAutomaticCategoryAreRemembered() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Test", expiryInDays: 100)
        let first = try await editor(kit, box: box)
        first.choose(CategoryID(rawValue: "skin-wound"))
        #expect(await first.save())
        var product = try #require(try await kit.named.namedProduct(gtin: CabinetTestKit.gtin))
        #expect(product.category.rawValue == "skin-wound" && product.categoryOverridden)

        let stored = try #require(try await kit.boxes.box(id: box.id))
        let second = try await editor(kit, box: stored)
        await second.resetToAutomatic()
        #expect(await second.save())
        product = try #require(try await kit.named.namedProduct(gtin: CabinetTestKit.gtin))
        #expect(product.category == .uncategorized && !product.categoryOverridden)
    }

    @Test func anExpiryOnlyChangeRemembersNothing() async throws {
        let kit = try await CabinetTestKit()
        let model = try await editor(kit, box: try await kit.add("Test", expiryInDays: 100))
        model.expiry = ExpiryChoice(kit.expiry(inDays: 200), editing: true)
        #expect(await model.save())
        #expect(try await kit.named.namedProduct(gtin: CabinetTestKit.gtin) == nil)
    }

    @Test func aKnownCodeAndABoxWithoutACodeRememberNothing() async throws {
        let kit = try await CabinetTestKit()
        let known = StubLookup(products: [CabinetTestKit.gtin: ReferenceProduct(name: "DOLIPRANE")])
        let model = try await editor(kit, box: try await kit.add("Doliprane", expiryInDays: 100), lookup: known)
        model.name = "Mon Doliprane"
        #expect(await model.save())
        #expect(try await kit.named.namedProduct(gtin: CabinetTestKit.gtin) == nil)

        let bare = try await editor(kit, box: try await kit.add("Thermomètre", gtin: nil, expiryInDays: 100))
        bare.name = "Thermomètre 2"
        #expect(await bare.save())
        #expect(try await kit.named.backfillPending())
    }

    @Test func aFailingMemoryWriteLeavesTheSaveSuccessful() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Test", expiryInDays: 100)
        let model = try await editor(kit, box: box, named: FailingRememberNamedProducts(base: kit.named))
        model.name = "Crème solaire"
        #expect(await model.save())
        #expect(!model.failed)
        #expect(try await kit.boxes.box(id: box.id)?.product.name == "Crème solaire")
    }
}
