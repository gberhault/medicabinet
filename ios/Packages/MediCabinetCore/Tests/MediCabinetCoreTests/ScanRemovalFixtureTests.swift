import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/scan-removal.json`, which the Android core also runs: first the pure policy, then
/// the service against the in-memory store (so the queries and the stock movement are covered too).
@Suite struct ScanRemovalFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.scanRemoval()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 15)
    }

    @Test(arguments: SharedFixtures.scanRemovalCases())
    func thePolicyMatchesTheSharedExpectation(_ fixtureCase: ScanRemovalCase) throws {
        let boxes = try fixtureCase.cabinet.map { try makeBox($0, location: UUID()) }
        let live = zip(fixtureCase.cabinet, boxes).filter { $0.0.state != "deleted" }.map(\.1)
        let code = try scannedCode(fixtureCase.scan)
        let serialMatch = live.first { $0.product.gtin == code.gtin && code.serial != nil && $0.serial == code.serial }
        let cabinet = live.filter { $0.isInCabinet && $0.product.gtin == code.gtin }

        let decision = RemovalPolicy.decide(code: code, serialMatch: serialMatch, cabinet: cabinet)

        #expect(decision.kind == fixtureCase.expect.decision)
        switch decision {
        case .remove(let id), .alreadyRemoved(let id):
            #expect(label(of: id) == fixtureCase.expect.box)
        case .choose(let ids):
            #expect(ids.map(label(of:)) == fixtureCase.expect.candidates)
        case .notInCabinet:
            break
        }
    }

    @Test(arguments: SharedFixtures.scanRemovalCases())
    func theServiceMatchesTheSharedExpectationAndWritesOnlyWhatItShould(_ fixtureCase: ScanRemovalCase) async throws {
        let clock = FixedClock(Date(timeIntervalSince1970: 1_800_000_000))
        let store = InMemoryCabinetStore(clock: clock)
        let location = Location(name: "Bathroom", isDefault: true, createdAt: clock.now)
        try await store.insert(location)
        for fixtureBox in fixtureCase.cabinet {
            let box = try makeBox(fixtureBox, location: location.id)
            try await store.insert(box)
            if fixtureBox.state == "removed" {
                try await store.markRemoved(id: box.id, reason: .thrownAway, at: Date(timeIntervalSince1970: 5))
            } else if fixtureBox.state == "deleted" {
                try await store.delete(id: box.id)
            }
        }
        let before = try await store.activeBoxes().map(\.id)
        let removal = ScanRemoval(boxes: store, clock: clock)

        let outcome = try await removal.handle(try scannedCode(fixtureCase.scan), reason: .usedUp)
        let after = try await store.activeBoxes().map(\.id)

        switch outcome {
        case .removed(let box):
            #expect(fixtureCase.expect.decision == "remove")
            #expect(label(of: box.id) == fixtureCase.expect.box)
            #expect(box.removal == Box.Removal(reason: .usedUp, date: clock.now))
            #expect(Set(after) == Set(before).subtracting([box.id]))
        case .choose(let candidates):
            #expect(fixtureCase.expect.decision == "choose")
            #expect(candidates.map { label(of: $0.id) } == fixtureCase.expect.candidates)
            #expect(after == before)
        case .alreadyRemoved(let box):
            #expect(fixtureCase.expect.decision == "alreadyRemoved")
            #expect(label(of: box.id) == fixtureCase.expect.box)
            #expect(after == before)
        case .notInCabinet:
            #expect(fixtureCase.expect.decision == "notInCabinet")
            #expect(after == before)
        }
    }
}

@Suite struct ScanRemovalTests {
    private struct Setup {
        let store: InMemoryCabinetStore
        let location: Location
        let clock: FixedClock
    }

    private func setup() async throws -> Setup {
        let clock = FixedClock(Date(timeIntervalSince1970: 1_800_000_000))
        let store = InMemoryCabinetStore(clock: clock)
        let location = Location(name: "Bathroom", isDefault: true, createdAt: clock.now)
        try await store.insert(location)
        return Setup(store: store, location: location, clock: clock)
    }

    private func box(_ location: Location, serial: String? = nil) -> Box {
        Box(product: ProductRef(gtin: "03400930000014", name: "Doliprane"), locationID: location.id,
            serial: serial, createdAt: Date(timeIntervalSince1970: 10))
    }

    private let code = ScannedCode(
        gtin: "03400930000014", expiry: .missing, lot: nil, serial: nil, symbology: .ean13)

    @Test func removingThenUndoingPutsTheBoxBackAsItWas() async throws {
        let env = try await setup()
        let (store, location, clock) = (env.store, env.location, env.clock)
        let original = box(location)
        try await store.insert(original)
        let removal = ScanRemoval(boxes: store, clock: clock)

        guard case .removed(let removed) = try await removal.handle(code, reason: .thrownAway) else {
            Issue.record("expected the only box to be removed")
            return
        }
        #expect(removed.removal?.reason == .thrownAway)
        #expect(try await store.activeBoxes().isEmpty)

        try await removal.undo(removed.id)
        let restored = try #require(try await store.box(id: original.id))
        #expect(restored.removal == nil)
        #expect(restored.product == original.product)
        #expect(restored.locationID == original.locationID)
    }

    @Test func pickingAmongCandidatesRemovesOnlyThePickedBox() async throws {
        let env = try await setup()
        let (store, location, clock) = (env.store, env.location, env.clock)
        let first = box(location, serial: "S1")
        let second = box(location, serial: "S2")
        try await store.insert(first)
        try await store.insert(second)
        let removal = ScanRemoval(boxes: store, clock: clock)

        guard case .choose(let candidates) = try await removal.handle(code, reason: .usedUp) else {
            Issue.record("two boxes of the product must ask which one")
            return
        }
        #expect(candidates.count == 2)
        let removed = try await removal.remove(second.id, reason: .usedUp)
        #expect(removed.id == second.id)
        #expect(try await store.activeBoxes().map(\.id) == [first.id])
    }

    @Test func removingAnUnknownBoxThrowsNotFound() async throws {
        let env = try await setup()
        let (store, clock) = (env.store, env.clock)
        let removal = ScanRemoval(boxes: store, clock: clock)
        await #expect(throws: RepositoryError.notFound) {
            try await removal.remove(UUID(), reason: .usedUp)
        }
    }

    @Test func decisionKindsUseThePlatformNeutralNames() {
        #expect(RemovalDecision.remove(UUID()).kind == "remove")
        #expect(RemovalDecision.choose([]).kind == "choose")
        #expect(RemovalDecision.alreadyRemoved(UUID()).kind == "alreadyRemoved")
        #expect(RemovalDecision.notInCabinet.kind == "notInCabinet")
    }
}

// MARK: - Fixture mapping

/// `bN` → UUID ending in N, so id order is numeric (the fixture's tie-break rule).
private func boxID(_ label: String) throws -> UUID {
    let number = try #require(Int(label.dropFirst()))
    return try #require(UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", number)))
}

private func label(of id: UUID) -> String {
    "b" + String(Int(id.uuidString.suffix(12)) ?? -1)
}

private func makeBox(_ fixtureBox: ScanRemovalCase.FixtureBox, location: UUID) throws -> Box {
    let expiry = try fixtureBox.expiry.map { try ExpiryDate.make(year: $0.year, month: $0.month, day: $0.day) }
    let removal = fixtureBox.state == "removed"
        ? Box.Removal(reason: .thrownAway, date: Date(timeIntervalSince1970: 5)) : nil
    return Box(
        id: try boxID(fixtureBox.id), product: ProductRef(gtin: fixtureBox.gtin, name: "Doliprane"),
        locationID: location, expiry: expiry, serial: fixtureBox.serial,
        createdAt: Date(timeIntervalSince1970: TimeInterval(fixtureBox.createdOrder)), removal: removal)
}

private func scannedCode(_ scan: ScanRemovalCase.Scan) throws -> ScannedCode {
    let expiry = try scan.expiry.map { try ExpiryDate.make(year: $0.year, month: $0.month, day: $0.day) }
    return ScannedCode(
        gtin: scan.gtin, expiry: expiry.map(ExpiryReading.date) ?? .missing, lot: nil, serial: scan.serial,
        symbology: .dataMatrix)
}
