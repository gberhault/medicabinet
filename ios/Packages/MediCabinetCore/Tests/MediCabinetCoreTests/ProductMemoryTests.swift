import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

@Suite struct ProductMemoryTests {
    private static let gtin = "03401900000003"

    /// Counts the lookups and answers from a table, or throws.
    private actor CountingReference: ReferenceDataSource {
        private let products: [String: ReferenceProduct]
        private let failing: Bool
        private let reference: ReferenceStatus
        private(set) var lookups = 0

        init(products: [String: ReferenceProduct] = [:], failing: Bool = false, available: Bool = true) {
            self.products = products
            self.failing = failing
            reference = available
                ? ReferenceDataDate(iso: "2026-10-18").map { .available(dataDate: $0) } ?? .unavailable(.unreadable)
                : .unavailable(.missing)
        }

        func product(gtin: String) async throws -> ReferenceProduct? {
            lookups += 1
            if failing { throw RepositoryError.notFound }
            return products[gtin]
        }

        func status() async -> ReferenceStatus { reference }
    }

    /// A named product repository whose `remember` always fails.
    private struct FailingNamed: NamedProductRepository {
        let base: any NamedProductRepository
        func namedProduct(gtin: String) async throws -> NamedProduct? { try await base.namedProduct(gtin: gtin) }
        func remember(gtin: String, name: String, category: CategoryID, categoryOverridden: Bool) async throws {
            throw RepositoryError.notFound
        }
        func observeSummaries() -> AsyncThrowingStream<[NamedProductSummary], any Error> { base.observeSummaries() }
        func rename(id: NamedProduct.ID, to name: String) async throws { try await base.rename(id: id, to: name) }
        func setCategory(id: NamedProduct.ID, _ category: CategoryID, overridden: Bool) async throws {
            try await base.setCategory(id: id, category, overridden: overridden)
        }
        func forget(id: NamedProduct.ID) async throws { try await base.forget(id: id) }
        func undoForget(id: NamedProduct.ID) async throws { try await base.undoForget(id: id) }
        func backfillPending() async throws -> Bool { try await base.backfillPending() }
        func completeBackfill(_ products: [NamedProduct]) async throws { try await base.completeBackfill(products) }
    }

    private let clock = MutableClock(Date(timeIntervalSince1970: 1_800_000_000))
    private let store: InMemoryCabinetStore
    private let location: Location

    init() async throws {
        store = InMemoryCabinetStore(clock: clock)
        location = try await store.ensureDefault(named: "Cabinet")
    }

    private func box(_ name: String = "Crème solaire", gtin: String? = ProductMemoryTests.gtin) -> Box {
        Box(product: ProductRef(gtin: gtin, name: name), locationID: location.id, createdAt: clock.now)
    }

    private func memory(_ reference: CountingReference) -> ProductMemory {
        ProductMemory(named: store.named, reference: reference)
    }

    // MARK: record

    @Test func anAddedBoxOfAnUnknownCodeIsRemembered() async throws {
        try await memory(CountingReference()).record(.added(box()))
        #expect(try await store.named.namedProduct(gtin: Self.gtin)?.name == "Crème solaire")
    }

    @Test func aKnownCodeRemembersNothing() async throws {
        let reference = CountingReference(products: [ProductMemoryTests.gtin: ReferenceProduct(name: "ARNICA")])
        try await memory(reference).record(.added(box()))
        #expect(try await store.named.namedProduct(gtin: Self.gtin) == nil)
        #expect(await reference.lookups == 1)
    }

    @Test func anUnavailableReferenceRemembersNothingAndIsNotAsked() async throws {
        let reference = CountingReference(available: false)
        try await memory(reference).record(.added(box()))
        #expect(try await store.named.namedProduct(gtin: Self.gtin) == nil)
        #expect(await reference.lookups == 0)
    }

    @Test func aFailingLookupRemembersNothing() async throws {
        try await memory(CountingReference(failing: true)).record(.added(box()))
        #expect(try await store.named.namedProduct(gtin: Self.gtin) == nil)
    }

    @Test func aBoxWithoutACodeRemembersNothingAndIsNotLookedUp() async throws {
        let reference = CountingReference()
        try await memory(reference).record(.added(box(gtin: nil)))
        #expect(await reference.lookups == 0)
        #expect(try await store.named.backfillPending())
    }

    @Test func aChangeThatTouchesNeitherNameNorCategoryCostsNoLookupAndKeepsAForgottenProductForgotten() async throws {
        let reference = CountingReference()
        let before = box()
        var after = before
        after.isOpened = true
        try await memory(reference).record(.changed(before: before, after: after))
        #expect(try await store.named.namedProduct(gtin: Self.gtin) == nil)
        #expect(await reference.lookups == 0)
    }

    @Test func aNewNameUpdatesTheProductAndAnEqualOneDoesNotWrite() async throws {
        let memory = memory(CountingReference())
        let before = box()
        try await memory.record(.added(before))
        let first = try #require(try await store.named.namedProduct(gtin: Self.gtin))
        clock.advance(by: 60)
        try await memory.record(.added(before))
        #expect(try await store.named.namedProduct(gtin: Self.gtin) == first)
        var after = before
        after.product.name = "Crème solaire SPF50"
        try await memory.record(.sessionCorrected(before: before, after: after))
        let updated = try #require(try await store.named.namedProduct(gtin: Self.gtin))
        #expect(updated.id == first.id && updated.name == "Crème solaire SPF50" && updated.updatedAt == clock.now)
    }

    @Test func aNewNameBringsAForgottenProductBackAsANewOne() async throws {
        let memory = memory(CountingReference())
        let before = box()
        try await memory.record(.added(before))
        let forgotten = try #require(try await store.named.namedProduct(gtin: Self.gtin))
        try await store.named.forget(id: forgotten.id)
        var after = before
        after.product.name = "Autre"
        try await memory.record(.changed(before: before, after: after))
        let created = try #require(try await store.named.namedProduct(gtin: Self.gtin))
        #expect(created.id != forgotten.id && created.name == "Autre")
    }

    @Test func aFailingWriteIsReportedByRecord() async throws {
        let memory = ProductMemory(named: FailingNamed(base: store.named), reference: CountingReference())
        await #expect(throws: RepositoryError.notFound) { try await memory.record(.added(box())) }
    }

    // MARK: the box is never undone by the memory

    private final class Reported: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        func report() { lock.withLock { count += 1 } }
        var value: Int { lock.withLock { count } }
    }

    @Test func aFailingMemoryWriteLeavesTheAddedBoxAndIsReported() async throws {
        let reported = Reported()
        let intake = ScanIntake(
            boxes: store, memory: ProductMemory(named: FailingNamed(base: store.named), reference: CountingReference()),
            clock: clock, onMemoryFailure: { _ in reported.report() }
        )
        let added = try await intake.add(BoxDraft(gtin: Self.gtin, name: "Crème solaire", serial: "S1"), location: location.id)
        #expect(try await store.box(id: added.id) == added)
        #expect(reported.value == 1)
    }

    @Test func addingAnUnknownCodeRemembersItsNameAndTheNextScanUsesIt() async throws {
        let intake = ScanIntake(boxes: store, memory: memory(CountingReference()), clock: clock)
        _ = try await intake.add(
            BoxDraft(gtin: Self.gtin, name: "Crème solaire", category: CategoryID(rawValue: "skin-wound"), categoryOverridden: true,
                     serial: "S1"),
            location: location.id
        )
        let resolved = try await memory(CountingReference()).resolve(gtin: Self.gtin, previousBox: nil)
        #expect(resolved == ResolvedProduct(name: "Crème solaire", category: CategoryID(rawValue: "skin-wound"), categoryOverridden: true))
    }

    // MARK: resolve

    @Test func theReferenceNameWinsAndANamedProductFillsInOtherwise() async throws {
        try await store.named.remember(gtin: Self.gtin, name: "Mon nom", category: .uncategorized, categoryOverridden: false)
        let known = CountingReference(products: [ProductMemoryTests.gtin: ReferenceProduct(name: "ARNICA")])
        #expect(try await memory(known).resolve(gtin: Self.gtin, previousBox: nil).name == "ARNICA")
        #expect(try await memory(CountingReference()).resolve(gtin: Self.gtin, previousBox: nil).name == "Mon nom")
        #expect(try await memory(CountingReference(failing: true)).resolve(gtin: Self.gtin, previousBox: nil).name == "Mon nom")
        #expect(try await memory(CountingReference(available: false)).resolve(gtin: Self.gtin, previousBox: nil).name == "Mon nom")
    }
}
