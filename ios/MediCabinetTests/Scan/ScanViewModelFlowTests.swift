import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

/// What happens to each scan (requirements D, I, K, R and the confirmation of C6).
@MainActor
@Suite struct ScanViewModelFlowTests {
    private let unknownProduct = StubLookup()

    // MARK: adding

    @Test func aCompleteCodeOfAKnownProductIsAddedAndConfirmed() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload())

        let stored = try await kit.storedBoxes()
        #expect(stored.count == 1)
        #expect(stored.first?.product.name == "DOLIPRANE")
        #expect(stored.first?.expiry == .date(2027, 3, 31))
        #expect(stored.first?.lot == "LOT1" && stored.first?.serial == "SER1")
        #expect(stored.first?.locationID == kit.defaultLocation.id)
        #expect(kit.model.session.count == 1)
        guard case let .added(box) = kit.model.notice else { Issue.record("expected an added notice"); return }
        #expect(box.id == stored.first?.id)
        #expect(kit.feedback.events == [.added])
    }

    @Test func aBoxHeldInFrontOfTheCameraIsCountedOnce() async throws {
        let kit = try await ScanTestKit.make()
        for seconds in stride(from: 0.0, through: 4.0, by: 0.2) { await kit.see(ScanTestKit.payload(), at: seconds) }
        #expect(try await kit.storedBoxes().count == 1)
        #expect(kit.feedback.events == [.added])
    }

    @Test func theSameSerialShownAgainIsRefusedWithItsLocation() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload(), at: 0)
        await kit.see(ScanTestKit.payload(), at: 5)
        #expect(try await kit.storedBoxes().count == 1)
        #expect(kit.model.notice == .alreadyInCabinet(try #require(try await kit.storedBoxes().first), locationName: "Cabinet"))
        #expect(kit.feedback.events == [.added, .rejected])
    }

    @Test func aRemovedBoxScannedAgainCanBePutBack() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload(), at: 0)
        let box = try #require(try await kit.storedBoxes().first)
        try await kit.boxes.markRemoved(id: box.id, reason: .usedUp, at: ScanTestKit.start)
        let kitchen = Location(name: "Kitchen", createdAt: ScanTestKit.start)
        try await kit.locations.insert(kitchen)
        await kit.model.loadLocations()
        kit.model.selectLocation(kitchen.id)
        kit.model.dismissNotice()

        await kit.see(ScanTestKit.payload(), at: 5)
        guard case let .removedEarlier(found) = kit.model.notice else { Issue.record("expected removedEarlier"); return }
        #expect(found.id == box.id)
        #expect(try await kit.storedBoxes().isEmpty)

        await kit.model.putBack(found)
        let restored = try #require(try await kit.storedBoxes().first)
        #expect(restored.id == box.id && restored.locationID == kitchen.id && restored.removal == nil)
        guard case .putBack = kit.model.notice else { Issue.record("expected putBack"); return }
    }

    @Test func anUnreadableCodeIsReportedOncePerPayload() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see("not a gs1 code", at: 0)
        #expect(kit.model.notice == .unrecognized)
        kit.model.dismissNotice()
        await kit.see("not a gs1 code", at: 5)
        #expect(kit.model.notice == nil)
        #expect(kit.feedback.events == [.rejected])
        #expect(try await kit.storedBoxes().isEmpty)
    }

    @Test func aFailingRepositoryShowsAnErrorInsteadOfCrashing() async throws {
        let kit = try await ScanTestKit.make(loadsLocations: false)
        // No location loaded: nothing to add to.
        await kit.see(ScanTestKit.payload())
        #expect(kit.model.notice == .failed)
        #expect(kit.feedback.events == [.rejected])
    }

    // MARK: completion

    @Test func anUnknownProductAsksForANameWhileTheBoxIsInHand() async throws {
        let kit = try await ScanTestKit.make(lookup: unknownProduct)
        await kit.see(ScanTestKit.payload())
        let completion = try #require(kit.model.completion)
        #expect(completion.missing == [.name])
        #expect(try await kit.storedBoxes().isEmpty)
        #expect(kit.feedback.events == [.attention])

        await kit.model.confirmCompletion(name: "  Mon sirop ", expiry: nil)
        #expect(kit.model.completion == nil)
        let box = try #require(try await kit.storedBoxes().first)
        #expect(box.product.name == "Mon sirop")
        #expect(box.expiry == .date(2027, 3, 31), "the date from the code is kept")
    }

    @Test func aCodeWithoutExpiryAsksForTheDateAndNeverDefaultsIt() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload(expiry: nil))
        let completion = try #require(kit.model.completion)
        #expect(completion.missing == [.expiry] && !completion.expiryUnreadable)
        #expect(completion.draft.expiry == nil)

        await kit.model.confirmCompletion(name: nil, expiry: .date(2028, 2, nil))
        #expect(try await kit.storedBoxes().first?.expiry == .date(2028, 2, nil))
    }

    @Test func dateNotPrintedSavesAnUnknownExpiry() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload(expiry: nil))
        await kit.model.confirmCompletion(name: nil, expiry: nil)
        #expect(try await kit.storedBoxes().first?.expiry == nil)
        #expect(kit.model.session.unknownExpiryCount == 1)
    }

    @Test func anImpossibleDateInTheCodeIsFlaggedUnreadableAndNotPrefilled() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload(expiry: "271315"))
        let completion = try #require(kit.model.completion)
        #expect(completion.expiryUnreadable && completion.draft.expiry == nil)
    }

    @Test func cancellingTheCompletionAddsNothing() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload(expiry: nil))
        kit.model.cancelCompletion()
        #expect(kit.model.completion == nil)
        #expect(try await kit.storedBoxes().isEmpty)
        #expect(kit.model.session.count == 0)
    }

    @Test func aBoxStillInFrontOfTheCameraIsNotRecountedWhenThePromptCloses() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload(expiry: nil), at: 0)
        #expect(kit.model.completion != nil)
        for seconds in stride(from: 0.2, through: 6.0, by: 0.2) { await kit.see(ScanTestKit.payload(expiry: nil), at: seconds) }
        await kit.model.confirmCompletion(name: nil, expiry: .date(2028, 1, 5))
        await kit.see(ScanTestKit.payload(expiry: nil), at: 6.2)
        #expect(try await kit.storedBoxes().count == 1)
        #expect(kit.model.completion == nil)
    }

    // MARK: another box

    @Test func theSameProductWithoutASerialAsksBeforeAddingAnotherBox() async throws {
        let kit = try await ScanTestKit.make()
        let noSerial = ScanTestKit.payload(lot: nil, serial: nil)
        await kit.see(noSerial, at: 0)
        #expect(try await kit.storedBoxes().count == 1)

        await kit.see(noSerial, at: 5)
        #expect(kit.model.another != nil)
        #expect(try await kit.storedBoxes().count == 1, "nothing is added until the user confirms")

        await kit.model.confirmAnother()
        #expect(kit.model.another == nil)
        #expect(try await kit.storedBoxes().count == 2)
    }

    @Test func theAnotherPromptDisappearsAtTheNextScan() async throws {
        let kit = try await ScanTestKit.make()
        let noSerial = ScanTestKit.payload(lot: nil, serial: nil)
        await kit.see(noSerial, at: 0)
        await kit.see(noSerial, at: 5)
        #expect(kit.model.another != nil)
        await kit.see(ScanTestKit.payload(serial: "OTHER"), at: 10)
        #expect(kit.model.another == nil)
    }

    @Test func confirmingAnotherBoxStillAsksForAMissingDate() async throws {
        let kit = try await ScanTestKit.make()
        let noExpiry = ScanTestKit.payload(expiry: nil, lot: nil, serial: nil)
        await kit.see(noExpiry, at: 0)
        await kit.model.confirmCompletion(name: nil, expiry: .date(2028, 1, 31))
        await kit.see(noExpiry, at: 5)
        await kit.model.confirmAnother()
        #expect(kit.model.completion?.missing == [.expiry])
        #expect(try await kit.storedBoxes().count == 1)
    }

    @Test func dismissingTheAnotherPromptAddsNothing() async throws {
        let kit = try await ScanTestKit.make()
        let noSerial = ScanTestKit.payload(lot: nil, serial: nil)
        await kit.see(noSerial, at: 0)
        await kit.see(noSerial, at: 5)
        kit.model.dismissAnother()
        #expect(kit.model.another == nil)
        #expect(try await kit.storedBoxes().count == 1)
    }

    // MARK: session list

    @Test func deletingABoxKeepsATombstoneAndCanBeUndone() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload())
        let box = try #require(try await kit.storedBoxes().first)

        await kit.model.delete(box)
        #expect(kit.model.session.count == 0)
        #expect(try await kit.storedBoxes().isEmpty)
        #expect(kit.model.notice == .deleted(box))

        await kit.model.undoDelete(box)
        #expect(kit.model.session.count == 1)
        #expect(try await kit.storedBoxes().map(\.id) == [box.id])
        #expect(kit.model.notice == nil)
    }

    @Test func aDeletedSerialCanBeScannedAgainAsANewBox() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload(), at: 0)
        await kit.model.delete(try #require(try await kit.storedBoxes().first))
        await kit.see(ScanTestKit.payload(), at: 5)
        #expect(try await kit.storedBoxes().count == 1)
        #expect(kit.model.session.count == 1)
    }

    @Test func aCorrectionChangesNameExpiryAndLocationAndCanSetTheExpiryBackToUnknown() async throws {
        let kit = try await ScanTestKit.make()
        let kitchen = Location(name: "Kitchen", createdAt: ScanTestKit.start)
        try await kit.locations.insert(kitchen)
        await kit.model.loadLocations()
        await kit.see(ScanTestKit.payload())
        let box = try #require(try await kit.storedBoxes().first)

        #expect(await kit.model.update(box, name: " Paracétamol ", expiry: .date(2029, 6, 1), locationID: kitchen.id))
        var saved = try #require(try await kit.storedBoxes().first)
        #expect(saved.product.name == "Paracétamol" && saved.expiry == .date(2029, 6, 1) && saved.locationID == kitchen.id)
        #expect(kit.model.session.newestFirst.first == saved)

        #expect(await kit.model.update(saved, name: "Paracétamol", expiry: nil, locationID: kitchen.id))
        saved = try #require(try await kit.storedBoxes().first)
        #expect(saved.expiry == nil)
    }

    @Test(arguments: ["", "   ", String(repeating: "x", count: 201)])
    func anInvalidNameIsRejectedWithoutChangingTheBox(_ name: String) async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload())
        let box = try #require(try await kit.storedBoxes().first)
        #expect(await kit.model.update(box, name: name, expiry: box.expiry, locationID: box.locationID) == false)
        #expect(try await kit.storedBoxes().first?.product.name == "DOLIPRANE")
    }

    // MARK: done and manual entry

    @Test func doneSummarisesTheIntakeAndClosingStartsAFreshSession() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload(serial: "A"), at: 0)
        await kit.see(ScanTestKit.payload(expiry: nil, serial: "B"), at: 0.1)
        await kit.model.confirmCompletion(name: nil, expiry: nil)

        kit.model.finish()
        #expect(kit.model.summary == ScanViewModel.Summary(added: 2, unknownExpiry: 1))
        #expect(kit.model.showsSummary)

        kit.model.closeSummary()
        #expect(!kit.model.showsSummary && kit.model.session.count == 0 && kit.model.notice == nil)
        #expect(try await kit.storedBoxes().count == 2, "the boxes were saved as they were added")
    }

    @Test func codesAreIgnoredWhileTheSummaryIsOpen() async throws {
        let kit = try await ScanTestKit.make()
        kit.model.finish()
        await kit.see(ScanTestKit.payload())
        #expect(try await kit.storedBoxes().isEmpty)
    }

    @Test func aBoxCanBeTypedInWithoutAnyCamera() async throws {
        let kit = try await ScanTestKit.make(access: .denied)
        await kit.model.loadLocations()
        let location = kit.defaultLocation.id
        let error = await kit.model.addManually(code: "3400930000014", name: "", expiry: .date(2028, 5, nil), locationID: location)
        #expect(error == nil)
        let box = try #require(try await kit.storedBoxes().first)
        #expect(box.product.name == "DOLIPRANE" && box.product.gtin == ScanTestKit.gtin)
        #expect(box.expiry == .date(2028, 5, nil))
        #expect(kit.model.session.count == 1)
    }

    @Test func aBoxWithoutCodeOrDateCanBeTypedIn() async throws {
        let kit = try await ScanTestKit.make(access: .denied)
        let error = await kit.model.addManually(code: "", name: "Thermomètre", expiry: nil, locationID: kit.defaultLocation.id)
        #expect(error == nil)
        let box = try #require(try await kit.storedBoxes().first)
        #expect(box.expiry == nil && box.product.gtin == nil)
    }

    @Test func typedEntriesAreValidated() async throws {
        let kit = try await ScanTestKit.make()
        let location = kit.defaultLocation.id
        #expect(await kit.model.addManually(code: "12345", name: "X", expiry: nil, locationID: location) == .invalidCode)
        #expect(await kit.model.addManually(code: "", name: "  ", expiry: nil, locationID: location) == .invalidName)
        #expect(try await kit.storedBoxes().isEmpty)
    }
}
