import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

@MainActor
@Suite struct BoxDetailViewModelTests {
    private struct Running {
        let kit: CabinetTestKit
        let model: BoxDetailViewModel
        let undo: UndoCenter
        let observation: Task<Void, Never>
        func finish() { observation.cancel() }
    }

    private func open(_ box: Box, in kit: CabinetTestKit) async -> Running {
        let undo = UndoCenter()
        let model = BoxDetailViewModel(boxID: box.id, environment: kit.environment, undo: undo)
        let observation = Task { await model.observe() }
        let running = Running(kit: kit, model: model, undo: undo, observation: observation)
        _ = await eventually { model.box != nil && !model.locations.isEmpty }
        return running
    }

    // MARK: Showing

    @Test func theDetailShowsTheBoxItsLocationAndItsStatus() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: 30, lot: "LOT1")
        let running = await open(box, in: kit)
        defer { running.finish() }
        #expect(running.model.box?.product.name == "Doliprane")
        #expect(running.model.box?.lot == "LOT1")
        #expect(running.model.locationName == "Home")
        #expect(running.model.status == .expiringSoon)
        #expect(!running.model.isGone)
    }

    @Test func anUnknownExpiryIsShownAsUnknownNeverAsOK() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Thermometer", gtin: nil)
        let running = await open(box, in: kit)
        defer { running.finish() }
        #expect(running.model.status == .unknown)
    }

    @Test func aBoxThatIsNotInTheCabinetIsGone() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane")
        try await kit.boxes.markRemoved(id: box.id, reason: .usedUp, at: kit.clock.now)
        let model = BoxDetailViewModel(boxID: box.id, environment: kit.environment, undo: nil)
        let observation = Task { await model.observe() }
        defer { observation.cancel() }
        #expect(await eventually { model.isGone })
        #expect(model.box == nil)
        #expect(model.status == nil)
        #expect(model.makeEditor() == nil)
    }

    @Test func theScreenFollowsTheBoxWhenItLeavesElsewhere() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane")
        let running = await open(box, in: kit)
        defer { running.finish() }
        try await kit.boxes.markRemoved(id: box.id, reason: .thrownAway, at: kit.clock.now)
        #expect(await eventually { running.model.isGone })
    }

    // MARK: Opened

    @Test func openedIsSavedAtOnceAndCanBeToggledBack() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane")
        let running = await open(box, in: kit)
        defer { running.finish() }
        await running.model.setOpened(true)
        #expect(running.model.box?.isOpened == true)
        #expect(try await kit.boxes.box(id: box.id)?.isOpened == true)
        await running.model.setOpened(false)
        #expect(try await kit.boxes.box(id: box.id)?.isOpened == false)
    }

    @Test func aFailedSaveOfOpenedChangesNothingAndRaisesTheAlert() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane")
        let running = await open(box, in: kit)
        defer { running.finish() }
        try kit.database.writer.close()
        await running.model.setOpened(true)
        #expect(running.model.actionFailed)
        #expect(running.model.box?.isOpened == false)
    }

    // MARK: Taking a box out

    @Test(arguments: [RemovalReason.usedUp, .thrownAway])
    func takingABoxOutRemovesItAtTheClockTimeAndOffersUndo(reason: RemovalReason) async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: 100, serial: "S1", lot: "L1")
        let running = await open(box, in: kit)
        defer { running.finish() }
        await running.model.takeOut(reason)

        let stored = try #require(try await kit.boxes.box(id: box.id))
        #expect(stored.removal == Box.Removal(reason: reason, date: kit.clock.now))
        #expect(running.model.isGone)
        let key = reason == .usedUp ? "undo.usedUp" : "undo.thrownAway"
        #expect(running.undo.pending?.message == CabinetText.undoMessage(key, name: "Doliprane"))
        #expect(running.undo.pending?.message.contains("Doliprane") == true)

        await running.undo.perform()
        let restored = try #require(try await kit.boxes.box(id: box.id))
        #expect(restored.removal == nil)
        #expect(restored.expiry == box.expiry)
        #expect(restored.product == box.product)
        #expect(restored.serial == "S1")
        #expect(restored.lot == "L1")
        #expect(running.undo.pending == nil)
    }

    @Test func aFailedRemovalChangesNothingAndOffersNoUndo() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane")
        let running = await open(box, in: kit)
        defer { running.finish() }
        try kit.database.writer.close()
        await running.model.takeOut(.usedUp)
        #expect(running.model.actionFailed)
        #expect(!running.model.isGone)
        #expect(running.undo.pending == nil)
    }

    @Test func addedByMistakeDeletesTheBoxWithoutAStockMovementAndUndoBringsItBack() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", serial: "S1")
        let running = await open(box, in: kit)
        defer { running.finish() }
        await running.model.deleteAsMistake()

        #expect(try await kit.boxes.box(id: box.id) == nil, "a deleted box is invisible")
        #expect(running.model.isGone)
        #expect(running.undo.pending?.message == CabinetText.undoMessage("undo.deleted", name: "Doliprane"))

        await running.undo.perform()
        let restored = try #require(try await kit.boxes.box(id: box.id))
        #expect(restored.removal == nil)
        #expect(restored.serial == "S1")
    }

    @Test func undoingADeletionWhoseSerialWasTakenSaysWhyAndKeepsItDeleted() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", serial: "S1")
        let running = await open(box, in: kit)
        defer { running.finish() }
        await running.model.deleteAsMistake()
        try await kit.add("Doliprane", serial: "S1")

        await running.undo.perform()
        #expect(running.undo.pending?.message == String(localized: "undo.restoreFailed"))
        #expect(running.undo.pending?.undo == nil, "a plain message: there is nothing left to undo")
        #expect(try await kit.boxes.box(id: box.id) == nil)
    }

    @Test func anUndoThatFailsForAnotherReasonSaysSo() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane")
        let running = await open(box, in: kit)
        defer { running.finish() }
        await running.model.takeOut(.usedUp)
        try kit.database.writer.close()
        await running.undo.perform()
        #expect(running.undo.pending?.message == String(localized: "box.error"))
    }

    @Test func withoutAnUndoCenterTheBoxIsStillTakenOut() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane")
        let model = BoxDetailViewModel(boxID: box.id, environment: kit.environment, undo: nil)
        let observation = Task { await model.observe() }
        defer { observation.cancel() }
        #expect(await eventually { model.box != nil })
        await model.takeOut(.usedUp)
        #expect(try await kit.boxes.box(id: box.id)?.removal != nil)
    }

    @Test func changingTheWindowUpdatesTheStatusShown() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: 45)
        let running = await open(box, in: kit)
        defer { running.finish() }
        #expect(running.model.status == .expiringSoon)
        kit.environment.preferences.window = .days30
        #expect(await eventually { running.model.status == .ok })
    }
}
