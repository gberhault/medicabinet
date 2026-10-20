import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

@Suite struct ScanIntakeTests {
    private struct StubLookup: ProductLookup {
        var products: [String: ReferenceProduct] = [:]
        var fails = false
        func product(gtin: String) async throws -> ReferenceProduct? {
            if fails { throw RepositoryError.notFound }
            return products[gtin]
        }
    }

    private static let gtin = "03400930000014"
    private static let march = (try? ExpiryDate.make(year: 2027, month: 3, day: nil))

    private let clock = MutableClock(Date(timeIntervalSince1970: 1_800_000_000))
    private let store: InMemoryCabinetStore
    private let location: Location

    init() async throws {
        store = InMemoryCabinetStore(clock: clock)
        location = try await store.ensureDefault(named: "Cabinet")
    }

    private func intake(_ lookup: StubLookup = StubLookup()) -> ScanIntake {
        ScanIntake(boxes: store, lookup: lookup, clock: clock)
    }

    private func code(
        expiry: ExpiryReading? = nil, serial: String? = "SER1", gtin: String = ScanIntakeTests.gtin
    ) -> ScannedCode {
        let reading = expiry ?? (Self.march.map { .date($0) } ?? .missing)
        return ScannedCode(gtin: gtin, expiry: reading, lot: "LOT1", serial: serial, symbology: .dataMatrix)
    }

    private let doliprane = StubLookup(products: [ScanIntakeTests.gtin: ReferenceProduct(name: "DOLIPRANE")])

    // MARK: evaluate

    @Test func aKnownProductWithADateIsReady() async throws {
        let outcome = try await intake(doliprane).evaluate(code(), sessionGTINs: [])
        guard case let .ready(draft) = outcome else { Issue.record("expected ready, got \(outcome)"); return }
        #expect(draft.name == "DOLIPRANE")
        #expect(draft.gtin == Self.gtin)
        #expect(draft.expiry == Self.march)
        #expect(draft.lot == "LOT1" && draft.serial == "SER1")
        #expect(draft.category == .uncategorized)
    }

    @Test func anUnknownProductNeedsAName() async throws {
        let outcome = try await intake().evaluate(code(), sessionGTINs: [])
        #expect(outcome == .needsCompletion(
            BoxDraft(gtin: Self.gtin, name: nil, expiry: Self.march, lot: "LOT1", serial: "SER1"),
            missing: [.name], expiryUnreadable: false
        ))
    }

    @Test func aMissingExpiryIsAskedNeverDefaulted() async throws {
        let outcome = try await intake(doliprane).evaluate(code(expiry: .missing), sessionGTINs: [])
        guard case let .needsCompletion(draft, missing, unreadable) = outcome else {
            Issue.record("expected completion, got \(outcome)"); return
        }
        #expect(draft.expiry == nil)
        #expect(missing == [.expiry] && !unreadable)
    }

    @Test func anInvalidExpiryIsFlaggedUnreadableAndNotPrefilled() async throws {
        let outcome = try await intake(doliprane).evaluate(code(expiry: .invalid(kind: "invalidDay")), sessionGTINs: [])
        guard case let .needsCompletion(draft, missing, unreadable) = outcome else {
            Issue.record("expected completion, got \(outcome)"); return
        }
        #expect(draft.expiry == nil)
        #expect(missing == [.expiry] && unreadable)
    }

    @Test func aSerialAlreadyInTheCabinetIsRefused() async throws {
        let service = intake(doliprane)
        let box = try await service.add(BoxDraft(gtin: Self.gtin, name: "DOLIPRANE", serial: "SER1"), location: location.id)
        let outcome = try await service.evaluate(code(), sessionGTINs: [])
        #expect(outcome == .alreadyInCabinet(box))
    }

    @Test func aRemovedBoxScannedAgainOffersToPutItBack() async throws {
        let service = intake(doliprane)
        let box = try await service.add(BoxDraft(gtin: Self.gtin, name: "DOLIPRANE", serial: "SER1"), location: location.id)
        try await store.markRemoved(id: box.id, reason: .usedUp, at: clock.now)
        let outcome = try await service.evaluate(code(), sessionGTINs: [])
        guard case let .removedEarlier(found) = outcome else { Issue.record("got \(outcome)"); return }
        #expect(found.id == box.id)
    }

    @Test func aDeletedBoxIsForgottenSoTheScanIsNew() async throws {
        let service = intake(doliprane)
        let box = try await service.add(BoxDraft(gtin: Self.gtin, name: "DOLIPRANE", serial: "SER1"), location: location.id)
        try await service.delete(box.id)
        guard case .ready = try await service.evaluate(code(), sessionGTINs: []) else {
            Issue.record("expected ready"); return
        }
    }

    @Test func sameProductWithoutSerialInTheSessionAsksForConfirmation() async throws {
        let outcome = try await intake(doliprane).evaluate(code(serial: nil), sessionGTINs: [Self.gtin])
        guard case let .confirmAnother(_, thenMissing, _) = outcome else { Issue.record("got \(outcome)"); return }
        #expect(thenMissing.isEmpty)
    }

    @Test func theNameOfAnEarlierBoxIsReusedWhenTheReferenceDoesNotKnowTheProduct() async throws {
        let service = intake()
        try await service.add(
            BoxDraft(gtin: Self.gtin, name: "Mon paracétamol", category: CategoryID(rawValue: "pain-fever"),
                     categoryOverridden: true, serial: "OLD"),
            location: location.id
        )
        let outcome = try await service.evaluate(code(), sessionGTINs: [])
        guard case let .ready(draft) = outcome else { Issue.record("got \(outcome)"); return }
        #expect(draft.name == "Mon paracétamol")
        #expect(draft.category.rawValue == "pain-fever" && draft.categoryOverridden)
    }

    @Test func aFailingLookupLeavesTheProductUnknownInsteadOfStoppingIntake() async throws {
        let outcome = try await intake(StubLookup(fails: true)).evaluate(code(), sessionGTINs: [])
        guard case let .needsCompletion(_, missing, _) = outcome else { Issue.record("got \(outcome)"); return }
        #expect(missing == [.name])
    }

    // MARK: the bundled reference data (CIP13 behind the GTIN)

    private let referenceDate = ReferenceDataDate(iso: "2026-10-18")

    private func reference() throws -> InMemoryReferenceData {
        InMemoryReferenceData(
            products: ["3400935955838": ReferenceProduct(name: "PARACETAMOL EXEMPLE 500 mg", category: CategoryID(rawValue: "pain-fever"))],
            dataDate: try #require(referenceDate)
        )
    }

    @Test func aGtinKnownToTheReferenceDataIsNamedAndCategorisedWithoutAsking() async throws {
        let service = ScanIntake(boxes: store, lookup: try reference(), clock: clock)
        let outcome = try await service.evaluate(code(gtin: "03400935955838"), sessionGTINs: [])
        guard case let .ready(draft) = outcome else { Issue.record("expected ready, got \(outcome)"); return }
        #expect(draft.name == "PARACETAMOL EXEMPLE 500 mg")
        #expect(draft.category.rawValue == "pain-fever" && !draft.categoryOverridden)
    }

    @Test func aGtinMissingFromTheReferenceDataStillAsksForAName() async throws {
        let service = ScanIntake(boxes: store, lookup: try reference(), clock: clock)
        guard case let .needsCompletion(_, missing, _) = try await service.evaluate(code(), sessionGTINs: []) else {
            Issue.record("expected completion"); return
        }
        #expect(missing == [.name])
    }

    @Test func unavailableReferenceDataLeavesEveryProductUnknownInsteadOfStoppingIntake() async throws {
        let service = ScanIntake(boxes: store, lookup: InMemoryReferenceData(status: .unavailable(.unreadable)), clock: clock)
        guard case let .needsCompletion(_, missing, _) = try await service.evaluate(code(gtin: "03400935955838"), sessionGTINs: []) else {
            Issue.record("expected completion"); return
        }
        #expect(missing == [.name])
    }

    // MARK: add

    @Test func addStoresTheBoxWithTheClockAndTheLocation() async throws {
        let draft = BoxDraft(
            gtin: Self.gtin, name: "  DOLIPRANE  ", category: CategoryID(rawValue: "pain-fever"),
            expiry: Self.march, lot: "L", serial: "S"
        )
        let box = try await intake().add(draft, location: location.id)
        #expect(box.product.name == "DOLIPRANE" && box.product.gtin == Self.gtin)
        #expect(box.locationID == location.id && box.createdAt == clock.now && box.updatedAt == clock.now)
        #expect(try await store.box(id: box.id) == box)
    }

    @Test func anUnknownExpiryStaysUnknown() async throws {
        let box = try await intake().add(BoxDraft(name: "Thermomètre"), location: location.id)
        #expect(box.expiry == nil)
        #expect(box.product.gtin == nil)
    }

    @Test(arguments: [nil, "", "   ", String(repeating: "x", count: 201)])
    func aMissingOrOverlongNameIsRejected(_ name: String?) async throws {
        await #expect(throws: IntakeError.invalidName) {
            try await intake().add(BoxDraft(name: name), location: location.id)
        }
        #expect(try await store.activeBoxes().isEmpty)
    }

    @Test func aNameOfTwoHundredCharactersIsAccepted() async throws {
        _ = try await intake().add(BoxDraft(name: String(repeating: "x", count: 200)), location: location.id)
    }

    @Test func addingTheSameSerialTwiceIsRefusedByTheRepository() async throws {
        let draft = BoxDraft(gtin: Self.gtin, name: "DOLIPRANE", serial: "SER1")
        try await intake().add(draft, location: location.id)
        await #expect(throws: RepositoryError.duplicateSerial) { try await intake().add(draft, location: location.id) }
    }

    // MARK: put back, delete

    @Test func putBackUndoesTheRemovalAndMovesTheBox() async throws {
        let service = intake()
        let other = Location(name: "Kitchen", createdAt: clock.now)
        try await store.insert(other)
        let box = try await service.add(BoxDraft(name: "X"), location: location.id)
        try await store.markRemoved(id: box.id, reason: .thrownAway, at: clock.now)
        let restored = try await service.putBack(box, to: other.id)
        #expect(restored.removal == nil && restored.locationID == other.id)
        #expect(try await store.activeBoxes().map(\.id) == [box.id])
    }

    @Test func putBackInTheSameLocationOnlyUndoesTheRemoval() async throws {
        let service = intake()
        let box = try await service.add(BoxDraft(name: "X"), location: location.id)
        try await store.markRemoved(id: box.id, reason: .usedUp, at: clock.now)
        let restored = try await service.putBack(box, to: location.id)
        #expect(restored.removal == nil && restored.locationID == location.id)
    }

    @Test func deleteAndUndoDeleteRoundTrip() async throws {
        let service = intake()
        let box = try await service.add(BoxDraft(name: "X"), location: location.id)
        try await service.delete(box.id)
        #expect(try await store.activeBoxes().isEmpty)
        try await service.undoDelete(box.id)
        #expect(try await store.activeBoxes().map(\.id) == [box.id])
    }

    // MARK: manual entry

    @Test func aTypedEanBecomesAGtinAndTakesTheReferenceName() async throws {
        let draft = try await intake(doliprane).manualDraft(code: "3400930000014", name: nil)
        #expect(draft.gtin == Self.gtin && draft.name == "DOLIPRANE")
        #expect(draft.expiry == nil && draft.serial == nil)
    }

    @Test func aTypedNameWinsOverTheReferenceName() async throws {
        let draft = try await intake(doliprane).manualDraft(code: "3400930000014", name: "  Mon sirop ")
        #expect(draft.name == "Mon sirop")
    }

    @Test(arguments: [nil, "", "  "])
    func noCodeGivesADraftWithoutGtin(_ code: String?) async throws {
        let draft = try await intake().manualDraft(code: code, name: "Pansements")
        #expect(draft.gtin == nil && draft.name == "Pansements")
        let nameless = try await intake().manualDraft(code: code, name: "  ")
        #expect(nameless.name == nil)
    }

    @Test(arguments: ["3400930000015", "12345", "abc", "034009300000145"])
    func aWrongCodeIsRejected(_ code: String) async throws {
        await #expect(throws: IntakeError.invalidCode) { try await intake().manualDraft(code: code, name: "X") }
    }

    @Test func intakeErrorsUseThePlatformNeutralNames() {
        #expect(IntakeError.invalidName.kind == "invalidName")
        #expect(IntakeError.invalidCode.kind == "invalidCode")
    }
}
