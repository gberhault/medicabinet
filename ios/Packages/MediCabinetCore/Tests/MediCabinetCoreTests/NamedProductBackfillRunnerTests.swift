import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

@Suite struct NamedProductBackfillRunnerTests {
    private static let arnica = "03401900000003"
    private static let thermometer = "03401900000010"

    /// Answers from a table, counts the lookups, and can run a hook (a scan happening meanwhile) or fail.
    private actor Reference: ReferenceDataSource {
        private let known: Set<String>
        private let failing: Bool
        private let reference: ReferenceStatus
        private let onLookup: (@Sendable () async -> Void)?
        private(set) var lookups = 0

        init(known: Set<String> = [], failing: Bool = false, available: Bool = true, onLookup: (@Sendable () async -> Void)? = nil) {
            self.known = known
            self.failing = failing
            self.onLookup = onLookup
            reference = available
                ? ReferenceDataDate(iso: "2026-10-18").map { .available(dataDate: $0) } ?? .unavailable(.unreadable)
                : .unavailable(.missing)
        }

        func product(gtin: String) async throws -> ReferenceProduct? {
            lookups += 1
            await onLookup?()
            if failing { throw RepositoryError.notFound }
            return known.contains(gtin) ? ReferenceProduct(name: "KNOWN") : nil
        }

        func status() async -> ReferenceStatus { reference }
    }

    private let clock = MutableClock(Date(timeIntervalSince1970: 1_800_000_000))
    private let store: InMemoryCabinetStore
    private let location: Location

    init() async throws {
        store = InMemoryCabinetStore(clock: clock)
        location = try await store.ensureDefault(named: "Cabinet")
    }

    private func runner(_ reference: Reference) -> NamedProductBackfillRunner {
        NamedProductBackfillRunner(named: store.named, boxes: store, reference: reference, clock: clock)
    }

    @discardableResult
    private func addBox(_ name: String, gtin: String?, overridden: Bool = false) async throws -> Box {
        let box = Box(
            product: ProductRef(gtin: gtin, name: name),
            category: overridden ? CategoryID(rawValue: "skin-wound") : .uncategorized,
            categoryOverridden: overridden, locationID: location.id, createdAt: clock.now
        )
        try await store.insert(box)
        clock.advance(by: 1)
        return box
    }

    @Test func pendingAndAvailableFillsTheUnknownCodesAndClearsTheMark() async throws {
        try await addBox("Arnica", gtin: Self.arnica, overridden: true)
        try await addBox("Doliprane", gtin: "03400930000014")
        try await addBox("Thermomètre", gtin: nil)
        let outcome = await runner(Reference(known: ["03400930000014"])).run()
        #expect(outcome == .completed(created: 1))
        let product = try #require(try await store.named.namedProduct(gtin: Self.arnica))
        #expect(product.name == "Arnica" && product.category.rawValue == "skin-wound" && product.categoryOverridden)
        #expect(try await store.named.namedProduct(gtin: "03400930000014") == nil)
        #expect(try await store.named.backfillPending() == false)
    }

    @Test func aRemovedNewestBoxStillNamesTheCode() async throws {
        try await addBox("Old name", gtin: Self.arnica)
        let newest = try await addBox("Newest name", gtin: Self.arnica)
        try await store.markRemoved(id: newest.id, reason: .usedUp, at: clock.now)
        _ = await runner(Reference()).run()
        #expect(try await store.named.namedProduct(gtin: Self.arnica)?.name == "Newest name")
    }

    @Test func anUnavailableReferenceWritesNothingAndStaysPending() async throws {
        try await addBox("Arnica", gtin: Self.arnica)
        let reference = Reference(available: false)
        #expect(await runner(reference).run() == .referenceUnavailable)
        #expect(await reference.lookups == 0)
        #expect(try await store.named.namedProduct(gtin: Self.arnica) == nil)
        #expect(try await store.named.backfillPending())
    }

    @Test func aThrowingLookupWritesNothingAndStaysPending() async throws {
        try await addBox("Arnica", gtin: Self.arnica)
        try await addBox("Thermomètre", gtin: Self.thermometer)
        let outcome = await runner(Reference(failing: true)).run()
        #expect(outcome == .interrupted(kind: "RepositoryError"))
        #expect(try await store.named.namedProduct(gtin: Self.arnica) == nil)
        #expect(try await store.named.backfillPending())
    }

    @Test func aFillThatAlreadyRanDoesNoLookup() async throws {
        try await addBox("Arnica", gtin: Self.arnica)
        try await store.named.completeBackfill([])
        let reference = Reference()
        #expect(await runner(reference).run() == .notPending)
        #expect(await reference.lookups == 0)
        #expect(try await store.named.namedProduct(gtin: Self.arnica) == nil)
    }

    @Test func aProductNamedMeanwhileIsKept() async throws {
        try await addBox("Arnica", gtin: Self.arnica)
        let named = store.named
        let scanMeanwhile = Reference(onLookup: {
            try? await named.remember(
                gtin: NamedProductBackfillRunnerTests.arnica, name: "Typed meanwhile", category: .uncategorized,
                categoryOverridden: false
            )
        })
        #expect(await runner(scanMeanwhile).run() == .completed(created: 1))
        #expect(try await store.named.namedProduct(gtin: Self.arnica)?.name == "Typed meanwhile")
        #expect(try await store.named.backfillPending() == false)
    }

    @Test func aCabinetWithNothingToFillStillClearsTheMark() async throws {
        #expect(await runner(Reference()).run() == .completed(created: 0))
        #expect(try await store.named.backfillPending() == false)
    }

    @Test func aThousandCodesAreFilledWithinTheBudget() async throws {
        for index in 0..<1_000 {
            try await store.insert(Box(
                product: ProductRef(gtin: "0340190" + String(format: "%07d", index), name: "Product \(index)"),
                locationID: location.id, createdAt: clock.now
            ))
        }
        let started = ContinuousClock.now
        let outcome = await runner(Reference()).run()
        let elapsed = ContinuousClock.now - started
        #expect(outcome == .completed(created: 1_000))
        #expect(elapsed < .seconds(5), "took \(elapsed)")
    }
}
