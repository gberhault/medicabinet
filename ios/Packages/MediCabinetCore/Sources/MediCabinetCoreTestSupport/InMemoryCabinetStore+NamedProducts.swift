import Foundation
import MediCabinetCore

/// The `NamedProductRepository` view of an `InMemoryCabinetStore`.
public struct InMemoryNamedProducts: NamedProductRepository {
    let store: InMemoryCabinetStore

    public func namedProduct(gtin: String) async throws -> NamedProduct? { try await store.namedProduct(gtin: gtin) }

    public func remember(gtin: String, name: String, category: CategoryID, categoryOverridden: Bool) async throws {
        try await store.remember(gtin: gtin, name: name, category: category, categoryOverridden: categoryOverridden)
    }

    public func observeSummaries() -> AsyncThrowingStream<[NamedProductSummary], any Error> { store.observeSummaries() }

    public func rename(id: NamedProduct.ID, to name: String) async throws {
        try await store.renameNamedProduct(id: id, to: name)
    }

    public func setCategory(id: NamedProduct.ID, _ category: CategoryID, overridden: Bool) async throws {
        try await store.setCategory(id: id, category, overridden: overridden)
    }

    public func forget(id: NamedProduct.ID) async throws { try await store.forget(id: id) }

    public func undoForget(id: NamedProduct.ID) async throws { try await store.undoForget(id: id) }

    public func backfillPending() async throws -> Bool { try await store.backfillPending() }

    public func completeBackfill(_ products: [NamedProduct]) async throws { try await store.completeBackfill(products) }
}

extension InMemoryCabinetStore {
    /// The named-product port of this store.
    public nonisolated var named: any NamedProductRepository { InMemoryNamedProducts(store: self) }

    // MARK: Named products

    func namedProduct(gtin: String) async throws -> NamedProduct? {
        liveProduct(gtin: gtin)
    }

    func remember(gtin: String, name: String, category: CategoryID, categoryOverridden: Bool) async throws {
        guard var stored = liveProduct(gtin: gtin) else {
            let product = NamedProduct(
                gtin: gtin, name: name, category: category, categoryOverridden: categoryOverridden, createdAt: clock.now
            )
            namedProducts[product.id] = product
            notify()
            return
        }
        guard stored.name != name || stored.category != category || stored.categoryOverridden != categoryOverridden else {
            return
        }
        stored.name = name
        stored.category = category
        stored.categoryOverridden = categoryOverridden
        stored.updatedAt = clock.now
        namedProducts[stored.id] = stored
        notify()
    }

    nonisolated func observeSummaries() -> AsyncThrowingStream<[NamedProductSummary], any Error> {
        AsyncThrowingStream { continuation in
            let token = UUID()
            Task { await self.addSummaryObserver(token, continuation) }
            continuation.onTermination = { _ in Task { await self.removeObserver(token) } }
        }
    }

    func renameNamedProduct(id: NamedProduct.ID, to name: String) throws {
        try apply(to: id) { product in product.name = name } onBox: { box in box.product.name = name }
    }

    func setCategory(id: NamedProduct.ID, _ category: CategoryID, overridden: Bool) throws {
        try apply(to: id) { product in
            product.category = category
            product.categoryOverridden = overridden
        } onBox: { box in
            box.category = category
            box.categoryOverridden = overridden
        }
    }

    func forget(id: NamedProduct.ID) async throws {
        guard var stored = liveProduct(id) else { throw RepositoryError.notFound }
        stored.updatedAt = clock.now
        namedProducts[id] = stored
        forgottenProducts.insert(id)
        notify()
    }

    func undoForget(id: NamedProduct.ID) async throws {
        guard var stored = namedProducts[id], forgottenProducts.contains(id) else { throw RepositoryError.notFound }
        guard liveProduct(gtin: stored.gtin) == nil else { throw RepositoryError.duplicate }
        stored.updatedAt = clock.now
        namedProducts[id] = stored
        forgottenProducts.remove(id)
        notify()
    }

    func backfillPending() async throws -> Bool {
        backfillIsPending
    }

    func completeBackfill(_ products: [NamedProduct]) async throws {
        for product in products where liveProduct(gtin: product.gtin) == nil {
            namedProducts[product.id] = product
        }
        backfillIsPending = false
        notify()
    }

    // MARK: Shared with the store

    func currentSummaries() -> [NamedProductSummary] {
        let inCabinet = currentActiveBoxes()
        let live = namedProducts.values.filter { !forgottenProducts.contains($0.id) }
        return NamedProductOrder.sorted(live.map { product in
            NamedProductSummary(product: product, boxesInCabinet: inCabinet.filter { $0.product.gtin == product.gtin }.count)
        })
    }

    func addSummaryObserver(
        _ token: UUID, _ continuation: AsyncThrowingStream<[NamedProductSummary], any Error>.Continuation
    ) {
        summaryObservers[token] = continuation
        continuation.yield(currentSummaries())
    }

    // MARK: Private

    private func liveProduct(_ id: NamedProduct.ID) -> NamedProduct? {
        forgottenProducts.contains(id) ? nil : namedProducts[id]
    }

    private func liveProduct(gtin: String) -> NamedProduct? {
        namedProducts.values.first { $0.gtin == gtin && !forgottenProducts.contains($0.id) }
    }

    /// One change to a live product and to every non-deleted box with its GTIN (removed ones included).
    private func apply(
        to id: NamedProduct.ID, onProduct: (inout NamedProduct) -> Void, onBox: (inout Box) -> Void
    ) throws {
        guard var stored = liveProduct(id) else { throw RepositoryError.notFound }
        onProduct(&stored)
        stored.updatedAt = clock.now
        namedProducts[id] = stored
        for var box in boxes.values where !deleted.contains(box.id) && box.product.gtin == stored.gtin {
            onBox(&box)
            box.updatedAt = clock.now
            boxes[box.id] = box
        }
        notify()
    }
}
