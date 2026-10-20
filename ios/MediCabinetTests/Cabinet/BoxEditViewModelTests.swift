import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@MainActor
@Suite struct BoxEditViewModelTests {
    private struct ThrowingLookup: ProductLookup {
        func product(gtin: String) async throws -> ReferenceProduct? { throw CancellationError() }
    }

    private func editor(
        _ kit: CabinetTestKit, box: Box, lookup: any ProductLookup = StubLookup()
    ) async throws -> BoxEditViewModel {
        BoxEditViewModel(box: box, locations: try await kit.locations.all(), boxes: kit.boxes, lookup: lookup)
    }

    // MARK: Starting point

    @Test func theFormStartsFromTheBoxAndNeverInventsADate() async throws {
        let kit = try await CabinetTestKit()
        let unknown = try await kit.add("Thermometer", gtin: nil)
        let model = try await editor(kit, box: unknown)
        #expect(model.name == "Thermometer")
        #expect(model.expiry.notPrinted, "an unknown expiry stays \"Date not printed\"")
        #expect(model.expiry.expiry == nil)
        #expect(model.canSave)
        #expect(model.locationID == kit.home.id)

        let dated = try await kit.add("Doliprane", expiryInDays: 100)
        let datedModel = try await editor(kit, box: dated)
        #expect(!datedModel.expiry.notPrinted)
        #expect(datedModel.expiry.expiry == dated.expiry)
    }

    @Test func untickingDatePrintedLeavesTheExpiryUndecidedAndCannotBeSaved() async throws {
        let kit = try await CabinetTestKit()
        let model = try await editor(kit, box: try await kit.add("Thermometer", gtin: nil))
        model.expiry.notPrinted = false
        #expect(model.expiry.resolved == .pending)
        #expect(!model.canSave)
        let saved = await model.save()
        #expect(!saved)
    }

    // MARK: Name

    @Test func anEmptyOrTooLongNameCannotBeSaved() async throws {
        let kit = try await CabinetTestKit()
        let model = try await editor(kit, box: try await kit.add("Doliprane", expiryInDays: 100))
        model.name = "   "
        #expect(model.nameProblem == .emptyName)
        #expect(!model.canSave)
        model.name = String(repeating: "a", count: 201)
        #expect(model.nameProblem == .nameTooLong)
        #expect(!model.canSave)
        model.name = String(repeating: "a", count: 200)
        #expect(model.nameProblem == nil)
        #expect(model.canSave)
    }

    // MARK: Save

    @Test func saveWritesTheNameTheExpiryAndTheLocationAndKeepsTheRest() async throws {
        let kit = try await CabinetTestKit()
        let car = try await kit.locations.create(named: "Car")
        let box = try await kit.add("Doliprane", expiryInDays: 100, serial: "S1", lot: "L1")
        let model = try await editor(kit, box: box)
        model.name = "  Doliprane 500  "
        model.locationID = car.id
        model.expiry = ExpiryChoice(nil, editing: true)
        let saved = await model.save()
        #expect(saved)

        let stored = try #require(try await kit.boxes.box(id: box.id))
        #expect(stored.product.name == "Doliprane 500")
        #expect(stored.product.gtin == box.product.gtin)
        #expect(stored.locationID == car.id)
        #expect(stored.expiry == nil, "\"Date not printed\" saves as unknown")
        #expect(stored.serial == "S1")
        #expect(stored.lot == "L1")
        #expect(stored.createdAt == box.createdAt)
    }

    @Test func aNewExpiryDateIsSaved() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: 100)
        let model = try await editor(kit, box: box)
        let date = try ExpiryDate.make(year: 2030, month: 6, day: nil)
        model.expiry = ExpiryChoice(date, editing: true)
        #expect(await model.save())
        #expect(try await kit.boxes.box(id: box.id)?.expiry == date)
    }

    @Test func aFailedSaveChangesNothingAndRaisesTheAlert() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: 100)
        let model = try await editor(kit, box: box)
        model.name = "Changed"
        try kit.database.writer.close()
        let saved = await model.save()
        #expect(!saved)
        #expect(model.failed)
        #expect(!model.isSaving)
    }

    @Test func aBoxThatLeftWhileEditingCannotBeSaved() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: 100)
        let model = try await editor(kit, box: box)
        try await kit.boxes.delete(id: box.id)
        let saved = await model.save()
        #expect(!saved)
        #expect(model.failed)
    }

    // MARK: Category (B4)

    @Test func theFormShowsTheBoxsOwnCategoryUntilItIsChanged() async throws {
        let kit = try await CabinetTestKit()
        let model = try await editor(kit, box: try await kit.add("Gaviscon", category: .digestion, expiryInDays: 100))
        #expect(model.category == .digestion)
        #expect(!model.isCategorySetByUser)
    }

    @Test func choosingACategoryMarksItAsSetByTheUserAndSavesIt() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: 100)
        let model = try await editor(kit, box: box)
        model.choose(.allergy)
        #expect(model.category == .allergy)
        #expect(model.isCategorySetByUser)
        #expect(await model.save())
        let stored = try #require(try await kit.boxes.box(id: box.id))
        #expect(stored.category == .allergy)
        #expect(stored.categoryOverridden)
    }

    @Test func uncategorizedCannotBePickedByTheUser() async throws {
        let kit = try await CabinetTestKit()
        let model = try await editor(kit, box: try await kit.add("Doliprane", category: .eyes, expiryInDays: 100))
        model.choose(.uncategorized)
        #expect(model.category == .eyes)
        #expect(!model.isCategorySetByUser)
    }

    @Test func automaticCategoryGivesTheBoxBackToTheClassifier() async throws {
        let kit = try await CabinetTestKit()
        let box = Box(
            product: ProductRef(gtin: CabinetTestKit.gtin, name: "Doliprane"), category: .eyes, categoryOverridden: true,
            locationID: kit.home.id, expiry: kit.expiry(inDays: 100), createdAt: kit.clock.now)
        try await kit.boxes.insert(box)
        let lookup = StubLookup(products: [CabinetTestKit.gtin: ReferenceProduct(name: "DOLIPRANE", category: .painFever)])
        let model = try await editor(kit, box: box, lookup: lookup)
        #expect(model.isCategorySetByUser)
        await model.resetToAutomatic()
        #expect(model.category == .painFever)
        #expect(!model.isCategorySetByUser)
        #expect(await model.save())
        let stored = try #require(try await kit.boxes.box(id: box.id))
        #expect(stored.category == .painFever)
        #expect(!stored.categoryOverridden)
    }

    @Test func automaticCategoryOfAnUnknownProductOrABoxWithoutACodeIsUncategorized() async throws {
        let kit = try await CabinetTestKit()
        let unknown = try await kit.add("Mystery", category: .eyes, expiryInDays: 100)
        let first = try await editor(kit, box: unknown)
        await first.resetToAutomatic()
        #expect(first.category == .uncategorized)

        let noCode = try await kit.add("Thermometer", gtin: nil, category: .firstAid, expiryInDays: 100)
        let second = try await editor(kit, box: noCode)
        second.choose(.eyes)
        await second.resetToAutomatic()
        #expect(second.category == .uncategorized)
        #expect(!second.isCategorySetByUser)
    }

    @Test func aFailedLookupChangesNothing() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", category: .eyes, expiryInDays: 100)
        let model = try await editor(kit, box: box, lookup: ThrowingLookup())
        await model.resetToAutomatic()
        #expect(model.failed)
        #expect(model.category == .eyes)
    }
}
