import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// How a scan resolves the name of a code the reference does not know: from the named product, never from a box (#57).
@Suite struct ScanIntakeNamedProductTests {
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
        ScanIntake(boxes: store, named: store.named, lookup: lookup, clock: clock)
    }

    private func code(serial: String? = "SER1", gtin: String = ScanIntakeNamedProductTests.gtin) -> ScannedCode {
        ScannedCode(
            gtin: gtin, expiry: Self.march.map { .date($0) } ?? .missing, lot: "LOT1", serial: serial, symbology: .dataMatrix
        )
    }

    private func reference() throws -> InMemoryReferenceData {
        InMemoryReferenceData(
            products: ["3400935955838": ReferenceProduct(name: "PARACETAMOL EXEMPLE 500 mg", category: CategoryID(rawValue: "pain-fever"))],
            dataDate: try #require(ReferenceDataDate(iso: "2026-10-18"))
        )
    }

    @Test func theNamedProductNameAndCategoryAreReusedWhenTheReferenceDoesNotKnowTheProduct() async throws {
        try await store.named.remember(
            gtin: Self.gtin, name: "Mon paracétamol", category: CategoryID(rawValue: "pain-fever"), categoryOverridden: true
        )
        let outcome = try await intake().evaluate(code(), sessionGTINs: [])
        guard case let .ready(draft) = outcome else { Issue.record("got \(outcome)"); return }
        #expect(draft.name == "Mon paracétamol")
        #expect(draft.category.rawValue == "pain-fever" && draft.categoryOverridden)
    }

    @Test func aForgottenProductAsksForANameAgain() async throws {
        try await store.named.remember(gtin: Self.gtin, name: "Mon paracétamol", category: .uncategorized, categoryOverridden: false)
        let id = try #require(try await store.named.namedProduct(gtin: Self.gtin)?.id)
        try await store.named.forget(id: id)
        guard case let .needsCompletion(_, missing, _) = try await intake().evaluate(code(), sessionGTINs: []) else {
            Issue.record("expected completion"); return
        }
        #expect(missing == [.name])
    }

    // Regression #57: the name of an earlier box, even a used-up one, no longer names a code the reference does not know.
    @Test func aUsedUpBoxWithATestNameNoLongerNamesTheNextScan() async throws {
        let box = Box(
            product: ProductRef(gtin: Self.gtin, name: "Test"), category: CategoryID(rawValue: "other"),
            categoryOverridden: true, locationID: location.id, serial: "OLD", createdAt: clock.now
        )
        try await store.insert(box)
        try await store.markRemoved(id: box.id, reason: .usedUp, at: clock.now)
        guard case let .needsCompletion(draft, missing, _) = try await intake().evaluate(code(), sessionGTINs: []) else {
            Issue.record("expected completion"); return
        }
        #expect(missing == [.name] && draft.name == nil)
        #expect(draft.category == .uncategorized && !draft.categoryOverridden)
    }

    @Test func theReferenceNameWinsOverTheNamedProductAndAPreviousUserCategoryStillCarriesOver() async throws {
        try await store.named.remember(gtin: "03400935955838", name: "Mon nom", category: .uncategorized, categoryOverridden: false)
        try await store.insert(Box(
            product: ProductRef(gtin: "03400935955838", name: "Old"), category: CategoryID(rawValue: "skin-wound"),
            categoryOverridden: true, locationID: location.id, createdAt: clock.now
        ))
        let service = ScanIntake(boxes: store, named: store.named, lookup: try reference(), clock: clock)
        guard case let .ready(draft) = try await service.evaluate(code(gtin: "03400935955838"), sessionGTINs: []) else {
            Issue.record("expected ready"); return
        }
        #expect(draft.name == "PARACETAMOL EXEMPLE 500 mg")
        #expect(draft.category.rawValue == "skin-wound" && draft.categoryOverridden)
    }

    @Test func aFailingLookupStillUsesTheNamedProduct() async throws {
        try await store.named.remember(gtin: Self.gtin, name: "Mon nom", category: .uncategorized, categoryOverridden: false)
        guard case let .ready(draft) = try await intake(StubLookup(fails: true)).evaluate(code(), sessionGTINs: []) else {
            Issue.record("expected ready"); return
        }
        #expect(draft.name == "Mon nom")
    }
}
