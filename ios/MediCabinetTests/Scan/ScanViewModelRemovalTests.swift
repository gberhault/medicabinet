import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

/// Remove mode of the scanner (requirements R1-R4, R6-R9 of the cabinet-inventory spec; issue #21).
@MainActor
@Suite struct ScanViewModelRemovalTests {
    private func stock(
        _ kit: ScanTestKit, serial: String?, expiry: ExpiryDate? = .date(2027, 3, 31), opened: Bool = false,
        removed: RemovalReason? = nil
    ) async throws -> Box {
        var box = Box(
            product: ProductRef(gtin: ScanTestKit.gtin, name: "DOLIPRANE"), locationID: kit.defaultLocation.id,
            expiry: expiry, serial: serial, isOpened: opened, createdAt: ScanTestKit.start
        )
        if let removed { box.removal = Box.Removal(reason: removed, date: ScanTestKit.start) }
        try await kit.boxes.insert(box)
        return box
    }

    private func stored(_ kit: ScanTestKit, _ id: Box.ID) async throws -> Box {
        try #require(try await kit.boxes.box(id: id))
    }

    // MARK: mode and reason (R1, R3)

    @Test func theScannerOpensInAddModeAndRemoveIsNeverRemembered() async throws {
        let kit = try await ScanTestKit.make()
        #expect(kit.model.mode == .add)
        kit.model.setMode(.remove)
        #expect(kit.model.mode == .remove)
        kit.model.resetMode()
        #expect(kit.model.mode == .add)
    }

    @Test func theReasonStartsAsUsedUpAndTheLastChoiceIsRemembered() async throws {
        let kit = try await ScanTestKit.make()
        #expect(kit.model.removalReason == .usedUp)
        kit.model.setRemovalReason(.thrownAway)
        #expect(kit.preferences.removalReason == .thrownAway)

        let defaults = try #require(UserDefaults(suiteName: "ScanViewModelRemovalTests-\(UUID().uuidString)"))
        let stored = UserDefaultsScanPreferences(defaults: defaults)
        #expect(stored.removalReason == .usedUp)
        stored.removalReason = .thrownAway
        #expect(UserDefaultsScanPreferences(defaults: defaults).removalReason == .thrownAway)
        #expect(defaults.string(forKey: "scan.removalReason") == "thrownAway")
    }

    @Test func aNewScreenStartsWithTheRememberedReason() async throws {
        let kit = try await ScanTestKit.make()
        kit.preferences.removalReason = .thrownAway
        let environment = AppEnvironment(
            locations: kit.locations, boxes: kit.boxes, clock: kit.clock,
            reference: InMemoryReferenceData(status: .unavailable(.missing))
        )
        let services = ScanServices(
            permission: kit.permission, makeScanner: { kit.scanner }, preferences: kit.preferences, feedback: kit.feedback,
            lookup: StubLookup()
        )
        #expect(ScanViewModel(environment: environment, services: services).removalReason == .thrownAway)
    }

    // MARK: outcomes (R5, R6)

    @Test func aSerialMatchIsRemovedWithTheSessionReasonAndConfirmed() async throws {
        let kit = try await ScanTestKit.make()
        let box = try await stock(kit, serial: "SER1")
        kit.model.setMode(.remove)
        kit.model.setRemovalReason(.thrownAway)
        await kit.see(ScanTestKit.payload())

        let removed = try await stored(kit, box.id)
        #expect(removed.removal == Box.Removal(reason: .thrownAway, date: ScanTestKit.start))
        #expect(kit.model.notice == .removed(removed, .thrownAway))
        #expect(kit.model.removalSession.count == 1)
        #expect(kit.model.session.count == 0, "nothing is added in Remove mode")
        #expect(kit.feedback.events == [.removed])
    }

    @Test func aSingleCandidateWithoutSerialIsRemoved() async throws {
        let kit = try await ScanTestKit.make()
        let box = try await stock(kit, serial: nil)
        kit.model.setMode(.remove)
        await kit.see(ScanTestKit.payload(serial: nil))
        #expect(try await stored(kit, box.id).removal?.reason == .usedUp)
    }

    @Test func aBoxThatIsNotInTheCabinetIsSaidSoAndNeverAdded() async throws {
        let kit = try await ScanTestKit.make()
        kit.model.setMode(.remove)
        await kit.see(ScanTestKit.payload())
        #expect(kit.model.notice == .notInCabinet)
        #expect(kit.feedback.events == [.rejected])
        #expect(try await kit.storedBoxes().isEmpty)
        #expect(kit.model.completion == nil)
    }

    @Test func aBoxAlreadyRemovedChangesNothing() async throws {
        let kit = try await ScanTestKit.make()
        let box = try await stock(kit, serial: "SER1", removed: .usedUp)
        kit.model.setMode(.remove)
        kit.model.setRemovalReason(.thrownAway)
        await kit.see(ScanTestKit.payload())
        #expect(kit.model.notice == .alreadyRemoved(try await stored(kit, box.id)))
        #expect(try await stored(kit, box.id).removal?.reason == .usedUp)
        #expect(kit.feedback.events == [.rejected])
        #expect(kit.model.removalSession.count == 0)
    }

    @Test func anUnreadableCodeBehavesAsInAddMode() async throws {
        let kit = try await ScanTestKit.make()
        kit.model.setMode(.remove)
        await kit.see("not a gs1 code", at: 0)
        #expect(kit.model.notice == .unrecognized)
        await kit.see("not a gs1 code", at: 5)
        #expect(kit.feedback.events == [.rejected])
    }

    // MARK: several candidates (R7)

    @Test func severalCandidatesAskWhichOneAndIgnoreRecognitionsWhileTheSheetIsOpen() async throws {
        let kit = try await ScanTestKit.make()
        let later = try await stock(kit, serial: nil, expiry: .date(2028, 1, 31))
        let sooner = try await stock(kit, serial: nil, expiry: .date(2026, 12, 31))
        kit.model.setMode(.remove)
        await kit.see(ScanTestKit.payload(serial: nil), at: 0)

        let candidates = try #require(kit.model.candidates)
        #expect(candidates.boxes.map(\.id) == [sooner.id, later.id], "first = earliest expiry, pre-selected")
        #expect(kit.feedback.events == [.attention])
        #expect(try await kit.storedBoxes().count == 2, "nothing removed yet")

        await kit.see(ScanTestKit.payload(gtin: "03400930000021", serial: "OTHER"), at: 10)
        #expect(kit.model.candidates == candidates, "recognitions are ignored while the sheet is open")
        #expect(kit.feedback.events == [.attention])

        await kit.model.removeCandidate(later.id)
        #expect(kit.model.candidates == nil)
        #expect(try await stored(kit, later.id).removal != nil)
        #expect(try await stored(kit, sooner.id).removal == nil)
        guard case let .removed(box, _) = kit.model.notice else { Issue.record("expected a removed notice"); return }
        #expect(box.id == later.id)
    }

    @Test func cancellingTheSheetRemovesNothing() async throws {
        let kit = try await ScanTestKit.make()
        _ = try await stock(kit, serial: nil, expiry: .date(2028, 1, 31))
        _ = try await stock(kit, serial: nil, expiry: .date(2026, 12, 31))
        kit.model.setMode(.remove)
        await kit.see(ScanTestKit.payload(serial: nil))
        kit.model.cancelCandidates()
        #expect(kit.model.candidates == nil)
        #expect(try await kit.storedBoxes().count == 2)
        #expect(kit.model.removalSession.count == 0)
    }

    @Test func aCandidateThatVanishedMeanwhileShowsAnError() async throws {
        let kit = try await ScanTestKit.make()
        await kit.model.removeCandidate(UUID())
        #expect(kit.model.notice == .failed)
    }

    // MARK: session, Undo, summary (R8)

    @Test func undoPutsTheBoxBackExactlyAsItWasAndLeavesTheSession() async throws {
        let kit = try await ScanTestKit.make()
        let box = try await stock(kit, serial: "SER1", opened: true)
        kit.model.setMode(.remove)
        await kit.see(ScanTestKit.payload())
        let removed = try await stored(kit, box.id)

        await kit.model.undoRemoval(removed)
        let restored = try await stored(kit, box.id)
        #expect(restored.removal == nil && restored.isOpened && restored.expiry == box.expiry)
        #expect(kit.model.removalSession.count == 0 && kit.model.notice == nil)
    }

    @Test func theSessionListsRemovalsNewestFirstAndTheSummaryCountsPerReason() async throws {
        let kit = try await ScanTestKit.make()
        let first = try await stock(kit, serial: "A")
        let second = try await stock(kit, serial: "B")
        let third = try await stock(kit, serial: "C")
        kit.model.setMode(.remove)
        await kit.see(ScanTestKit.payload(serial: "A"), at: 0)
        kit.model.setRemovalReason(.thrownAway)
        await kit.see(ScanTestKit.payload(serial: "B"), at: 5)
        await kit.see(ScanTestKit.payload(serial: "C"), at: 10)
        #expect(kit.model.removalSession.newestFirst.map(\.id) == [third.id, second.id, first.id])

        kit.model.finish()
        let summary = try #require(kit.model.summary)
        #expect(summary.mode == .remove && summary.usedUp == 1 && summary.thrownAway == 2)
        kit.model.closeSummary()
        #expect(kit.model.removalSession.count == 0 && kit.model.summary == nil)
        #expect(try await kit.storedBoxes().isEmpty, "removals were saved at once")
    }

    @Test func switchingBackToAddEndsTheRemoveSessionButKeepsTheRemovals() async throws {
        let kit = try await ScanTestKit.make()
        let box = try await stock(kit, serial: "SER1")
        kit.model.setMode(.remove)
        await kit.see(ScanTestKit.payload())
        kit.model.setMode(.add)
        #expect(kit.model.removalSession.count == 0 && kit.model.notice == nil)
        #expect(try await stored(kit, box.id).removal != nil)
    }

    @Test func aRemovedBoxScannedInAddModeCanStillBePutBack() async throws {
        let kit = try await ScanTestKit.make()
        let box = try await stock(kit, serial: "SER1")
        kit.model.setMode(.remove)
        await kit.see(ScanTestKit.payload(), at: 0)
        kit.model.setMode(.add)
        await kit.see(ScanTestKit.payload(), at: 10)
        guard case let .removedEarlier(found) = kit.model.notice else { Issue.record("expected removedEarlier"); return }
        #expect(found.id == box.id)
    }

    @Test func switchingModeClosesAPendingPrompt() async throws {
        let kit = try await ScanTestKit.make()
        _ = try await stock(kit, serial: nil, expiry: .date(2028, 1, 31))
        _ = try await stock(kit, serial: nil, expiry: .date(2026, 12, 31))
        kit.model.setMode(.remove)
        await kit.see(ScanTestKit.payload(serial: nil))
        #expect(kit.model.candidates != nil)
        kit.model.setMode(.add)
        #expect(kit.model.candidates == nil)
    }

    // MARK: performance (§8)

    @Test func handlingARemovalOnAThousandBoxCabinetStaysUnderTheBudget() async throws {
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        let clock = FixedClock(Date(timeIntervalSince1970: 1_800_000_000))
        let boxes = GRDBBoxRepository(database: database, clock: clock)
        let location = try await GRDBLocationRepository(database: database, clock: clock).ensureDefault(named: "Cabinet")
        try await database.writer.write { db in
            for index in 0..<1_000 {
                let box = Box(
                    product: ProductRef(gtin: "0340093" + String(format: "%07d", index), name: "Product \(index)"),
                    locationID: location.id, serial: "S\(index)", createdAt: clock.now
                )
                try BoxRecord(box).insert(db)
            }
        }
        let removal = ScanRemoval(boxes: boxes, clock: clock)
        var durations: [Duration] = []
        for run in 0..<10 {
            let code = ScannedCode(
                gtin: "0340093" + String(format: "%07d", 500 + run), expiry: .missing, lot: "L", serial: "S\(500 + run)",
                symbology: .dataMatrix
            )
            let started = ContinuousClock.now
            guard case .removed = try await removal.handle(code, reason: .usedUp) else {
                Issue.record("expected a removal")
                return
            }
            durations.append(ContinuousClock.now - started)
        }
        let median = durations.sorted()[durations.count / 2]
        #expect(median < .milliseconds(100), "median \(median)")
    }
}
