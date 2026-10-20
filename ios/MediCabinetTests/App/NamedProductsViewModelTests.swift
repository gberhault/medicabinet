import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@MainActor
@Suite struct NamedProductsViewModelTests {
    private static let arnica = "03401900000003"
    private static let thermometer = "03401900000010"
    private static let skin = CategoryID(rawValue: "skin-wound")

    /// The real GRDB repositories on an in-memory database: the model is tested against what ships.
    @MainActor private struct Setup {
        let kit: CabinetTestKit
        let undo: UndoCenter
        let model: NamedProductsViewModel
        let observation: Task<Void, Never>

        func finish() { observation.cancel() }

        var summaries: [NamedProductSummary] {
            if case .loaded(let summaries) = model.phase { return summaries }
            return []
        }

        var names: [String] { summaries.map(\.product.name) }
    }

    private func setup(products: [(String, String)] = []) async throws -> Setup {
        let kit = try await CabinetTestKit()
        for (gtin, name) in products {
            try await kit.named.remember(gtin: gtin, name: name, category: .uncategorized, categoryOverridden: false)
        }
        let undo = UndoCenter()
        let model = NamedProductsViewModel(repository: kit.named, undo: undo)
        let observation = Task { await model.observe() }
        let ready = await eventually { if case .loaded = model.phase { true } else { false } }
        try #require(ready, "the list never loaded")
        return Setup(kit: kit, undo: undo, model: model, observation: observation)
    }

    private func summary(_ env: Setup, _ gtin: String) throws -> NamedProductSummary {
        try #require(env.summaries.first { $0.product.gtin == gtin })
    }

    private func bundle(_ code: String) throws -> Bundle {
        let path = try #require(Bundle.main.path(forResource: code, ofType: "lproj"), "no \(code).lproj in the app")
        return try #require(Bundle(path: path))
    }

    // MARK: The list

    @Test func theListLoadsInNameOrderWithTheBoxesInTheCabinet() async throws {
        let env = try await setup(products: [(Self.thermometer, "Zinc"), (Self.arnica, "Écharpe"), ("03401900000027", "arnica")])
        defer { env.finish() }
        #expect(env.names == ["arnica", "Écharpe", "Zinc"])
        try await env.kit.add("Écharpe", gtin: Self.arnica)
        try await env.kit.add("Écharpe", gtin: Self.arnica)
        let removed = try await env.kit.add("Écharpe", gtin: Self.arnica)
        try await env.kit.boxes.markRemoved(id: removed.id, reason: .usedUp, at: env.kit.clock.now)
        #expect(await eventually { env.summaries.map(\.boxesInCabinet) == [0, 2, 0] })
    }

    @Test func anEmptyListIsLoadedNotLoading() async throws {
        let env = try await setup()
        defer { env.finish() }
        #expect(env.model.phase == .loaded([]))
    }

    @Test func theListFollowsChangesMadeElsewhere() async throws {
        let env = try await setup()
        defer { env.finish() }
        try await env.kit.named.remember(gtin: Self.arnica, name: "Arnica", category: Self.skin, categoryOverridden: true)
        #expect(await eventually { env.names == ["Arnica"] })
        let product = try summary(env, Self.arnica).product
        try await env.kit.named.rename(id: product.id, to: "Arnica gel")
        #expect(await eventually { env.names == ["Arnica gel"] })
    }

    @Test func aListThatCannotBeReadShowsTheFailedState() async throws {
        let kit = try await CabinetTestKit()
        try kit.database.writer.close()
        let model = NamedProductsViewModel(repository: kit.named, undo: nil)
        await model.observe()
        #expect(model.phase == .failed)
    }

    @Test(arguments: [("fr", 1, "1 boîte dans l'armoire"), ("fr", 3, "3 boîtes dans l'armoire"),
                      ("en", 1, "1 box in the cabinet"), ("en", 3, "3 boxes in the cabinet"),
                      ("fr", 0, "Pas dans l'armoire"), ("en", 0, "Not in the cabinet")])
    func theBoxCountIsPluralAwareInEachLanguage(code: String, count: Int, expected: String) async throws {
        let env = try await setup()
        defer { env.finish() }
        let product = NamedProduct(gtin: Self.arnica, name: "A", createdAt: Date(timeIntervalSince1970: 0))
        let text = env.model.boxCount(
            of: NamedProductSummary(product: product, boxesInCabinet: count), bundle: try bundle(code), locale: Locale(identifier: code))
        #expect(text == expected)
    }

    @Test func aRowShowsTheCodeAsBoxDetailDoesAndTheCategoryTheBoxesWillGet() async throws {
        let env = try await setup()
        defer { env.finish() }
        let chosen = NamedProduct(gtin: Self.arnica, name: "A", category: Self.skin, categoryOverridden: true, createdAt: Date())
        let automatic = NamedProduct(gtin: Self.arnica, name: "A", category: Self.skin, categoryOverridden: false, createdAt: Date())
        #expect(env.model.code(of: chosen) == "3401900000003")
        #expect(env.model.category(of: chosen) == CategoryLabel.text(for: Self.skin))
        #expect(env.model.category(of: automatic) == CategoryLabel.text(for: .uncategorized))
    }

    // MARK: Edit

    @Test func renamingChangesTheProductAndEveryBoxWithItsCode() async throws {
        let env = try await setup(products: [(Self.arnica, "Test")])
        defer { env.finish() }
        let inCabinet = try await env.kit.add("Test", gtin: Self.arnica)
        let used = try await env.kit.add("Test", gtin: Self.arnica)
        try await env.kit.boxes.markRemoved(id: used.id, reason: .usedUp, at: env.kit.clock.now)
        let row = try summary(env, Self.arnica)
        var edit = NamedProductEdit(from: row.product)
        edit.name = "  Crème solaire "
        #expect(await env.model.save(edit, of: row) == .saved)
        #expect(await eventually { env.names == ["Crème solaire"] })
        #expect(try await env.kit.boxes.box(id: inCabinet.id)?.product.name == "Crème solaire")
        #expect(try await env.kit.boxes.box(id: used.id)?.product.name == "Crème solaire")
        #expect(!env.model.changeFailed)
    }

    @Test func changingTheCategoryChangesTheProductAndItsBoxes() async throws {
        let env = try await setup(products: [(Self.arnica, "Arnica")])
        defer { env.finish() }
        let box = try await env.kit.add("Arnica", gtin: Self.arnica)
        let row = try summary(env, Self.arnica)
        var edit = NamedProductEdit(from: row.product)
        try edit.chooseCategory(Self.skin)
        #expect(await env.model.save(edit, of: row) == .saved)
        #expect(await eventually { env.summaries.first?.product.category == Self.skin })
        let stored = try #require(try await env.kit.boxes.box(id: box.id))
        #expect(stored.category == Self.skin && stored.categoryOverridden && stored.product.name == "Arnica")
    }

    @Test func aNameAndACategoryAreSavedTogether() async throws {
        let env = try await setup(products: [(Self.arnica, "Test")])
        defer { env.finish() }
        let row = try summary(env, Self.arnica)
        var edit = NamedProductEdit(from: row.product)
        edit.name = "Arnica"
        try edit.chooseCategory(Self.skin)
        #expect(await env.model.save(edit, of: row) == .saved)
        #expect(await eventually {
            env.summaries.first?.product.name == "Arnica" && env.summaries.first?.product.categoryOverridden == true
        })
    }

    @Test func aFormWithNothingChangedWritesNothing() async throws {
        let env = try await setup(products: [(Self.arnica, "Arnica")])
        defer { env.finish() }
        let row = try summary(env, Self.arnica)
        env.kit.clock.advance(by: 60)
        #expect(await env.model.save(NamedProductEdit(from: row.product), of: row) == .saved)
        #expect(try await env.kit.named.namedProduct(gtin: Self.arnica)?.updatedAt == row.product.updatedAt)
    }

    @Test func anInvalidNameIsRefusedAndNothingIsWritten() async throws {
        let env = try await setup(products: [(Self.arnica, "Arnica")])
        defer { env.finish() }
        let row = try summary(env, Self.arnica)
        var edit = NamedProductEdit(from: row.product)
        edit.name = "   "
        #expect(await env.model.save(edit, of: row) == .invalid(.emptyName))
        edit.name = String(repeating: "x", count: 201)
        #expect(await env.model.save(edit, of: row) == .invalid(.nameTooLong))
        #expect(try await env.kit.named.namedProduct(gtin: Self.arnica)?.name == "Arnica")
        #expect(!env.model.changeFailed)
    }

    @Test func aFailedWriteRaisesTheAlertAndKeepsTheList() async throws {
        let env = try await setup(products: [(Self.arnica, "Arnica")])
        defer { env.finish() }
        let row = try summary(env, Self.arnica)
        var edit = NamedProductEdit(from: row.product)
        edit.name = "Autre"
        try env.kit.database.writer.close()
        #expect(await env.model.save(edit, of: row) == .failed)
        #expect(env.model.changeFailed)
        #expect(env.names == ["Arnica"])
    }

    @Test func editingOpensTheSheetForThatProduct() async throws {
        let env = try await setup(products: [(Self.arnica, "Arnica")])
        defer { env.finish() }
        let row = try summary(env, Self.arnica)
        env.model.edit(row)
        #expect(env.model.editing == row)
    }

    // MARK: Forget

    @Test(arguments: [("en", 0, "The next scan of this code will ask you for a name."),
                      ("en", 1, "1 box is still in the cabinet and keeps its name."),
                      ("en", 2, "2 boxes are still in the cabinet and keep their name."),
                      ("fr", 0, "Le prochain scan de ce code vous demandera un nom."),
                      ("fr", 1, "1 boîte est encore dans l'armoire et garde son nom."),
                      ("fr", 2, "2 boîtes sont encore dans l'armoire et gardent leur nom.")])
    func theForgetMessageSaysHowManyBoxesStayAndThatTheNextScanAsks(code: String, count: Int, expected: String) async throws {
        let env = try await setup()
        defer { env.finish() }
        let product = NamedProduct(gtin: Self.arnica, name: "Arnica", createdAt: Date(timeIntervalSince1970: 0))
        let request = NamedProductsViewModel.ForgetRequest(summary: NamedProductSummary(product: product, boxesInCabinet: count))
        let message = env.model.forgetMessage(for: request, bundle: try bundle(code), locale: Locale(identifier: code))
        #expect(message.contains(expected), "message was \(message)")
        #expect(env.model.forgetTitle(for: request, bundle: try bundle(code)).contains("Arnica"))
    }

    @Test func cancellingTheConfirmationChangesNothing() async throws {
        let env = try await setup(products: [(Self.arnica, "Arnica")])
        defer { env.finish() }
        env.model.requestForget(try summary(env, Self.arnica))
        #expect(env.model.forgetRequest != nil)
        env.model.cancelForget()
        #expect(env.model.forgetRequest == nil)
        #expect(env.names == ["Arnica"])
        #expect(env.undo.pending == nil)
    }

    @Test func confirmingForgetsTheProductKeepsItsBoxesAndOffersUndo() async throws {
        let env = try await setup(products: [(Self.arnica, "Arnica")])
        defer { env.finish() }
        let box = try await env.kit.add("Arnica", gtin: Self.arnica)
        env.model.requestForget(try summary(env, Self.arnica))
        await env.model.confirmForget()
        #expect(env.model.forgetRequest == nil)
        #expect(await eventually { env.names.isEmpty })
        #expect(try await env.kit.boxes.box(id: box.id)?.product.name == "Arnica", "the box keeps its name")
        let pending = try #require(env.undo.pending)
        #expect(pending.message.contains("Arnica") && pending.undo != nil)

        await env.undo.perform()
        #expect(await eventually { env.names == ["Arnica"] })
    }

    @Test func anUndoThatCannotRestoreSaysSoAndChangesNothing() async throws {
        let env = try await setup(products: [(Self.arnica, "Arnica")])
        defer { env.finish() }
        env.model.requestForget(try summary(env, Self.arnica))
        await env.model.confirmForget()
        try await env.kit.named.remember(gtin: Self.arnica, name: "Named again", category: .uncategorized, categoryOverridden: false)
        await env.undo.perform()
        let pending = try #require(env.undo.pending)
        #expect(pending.message == String(localized: "namedProducts.undoFailed") && pending.undo == nil)
        #expect(await eventually { env.names == ["Named again"] })
    }

    @Test func aFailedForgetRaisesTheAlertAndOffersNoUndo() async throws {
        let env = try await setup(products: [(Self.arnica, "Arnica")])
        defer { env.finish() }
        env.model.requestForget(try summary(env, Self.arnica))
        try env.kit.database.writer.close()
        await env.model.confirmForget()
        #expect(env.model.changeFailed)
        #expect(env.undo.pending == nil)
    }
}
