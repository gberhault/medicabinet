import Foundation

/// The one-time fill of the named products after the upgrade to schema v4 (#57): every code the reference does not
/// name gets the name and category of its newest box.
public enum NamedProductBackfill {
    /// `latest`: the newest non-deleted box of every GTIN (`BoxRepository.latestBoxPerGTIN`). `isKnown`: the
    /// reference lookup. A box without a GTIN, or with a known one, gives nothing. The category and its flag are the
    /// box's when its flag is on, else `uncategorized` with the flag off. Ordered by GTIN.
    public static func products(
        latest: [Box], isKnown: (String) -> Bool, ids: () -> UUID, now: Date
    ) -> [NamedProduct] {
        latest.compactMap { box -> NamedProduct? in
            guard let gtin = box.product.gtin, !isKnown(gtin) else { return nil }
            return NamedProduct(
                id: ids(), gtin: gtin, name: box.product.name,
                category: box.categoryOverridden ? box.category : .uncategorized,
                categoryOverridden: box.categoryOverridden, createdAt: now
            )
        }
        .sorted { TextNormalizer.compare($0.gtin, $1.gtin) < 0 }
    }
}

/// Runs the fill once. It never throws: a run that cannot finish writes nothing and is retried at the next launch.
public struct NamedProductBackfillRunner: Sendable {
    public enum Outcome: Equatable, Sendable {
        /// The fill had already run.
        case notPending
        /// The reference data cannot tell known codes from unknown ones; retried at a later launch.
        case referenceUnavailable
        /// A lookup or the store failed (the error type name only, never a message); nothing was written.
        case interrupted(kind: String)
        /// The fill ran and cleared its mark; `created` named products were added.
        case completed(created: Int)
    }

    private let named: any NamedProductRepository
    private let boxes: any BoxRepository
    private let reference: any ReferenceDataSource
    private let clock: any Clock
    private let ids: @Sendable () -> UUID

    public init(
        named: any NamedProductRepository, boxes: any BoxRepository, reference: any ReferenceDataSource,
        clock: any Clock, ids: @escaping @Sendable () -> UUID = { UUID() }
    ) {
        self.named = named
        self.boxes = boxes
        self.reference = reference
        self.clock = clock
        self.ids = ids
    }

    public func run() async -> Outcome {
        do {
            guard try await named.backfillPending() else { return .notPending }
            guard case .available = await reference.status() else { return .referenceUnavailable }
            let latest = try await boxes.latestBoxPerGTIN()
            var known = Set<String>()
            for gtin in Set(latest.compactMap(\.product.gtin)) {
                let isKnown = try await reference.product(gtin: gtin) != nil
                if isKnown { known.insert(gtin) }
            }
            let products = NamedProductBackfill.products(
                latest: latest, isKnown: { known.contains($0) }, ids: ids, now: clock.now
            )
            try await named.completeBackfill(products)
            return .completed(created: products.count)
        } catch {
            return .interrupted(kind: String(describing: type(of: error)))
        }
    }
}
