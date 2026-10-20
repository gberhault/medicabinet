import Foundation
import MediCabinetCore

/// A named-product repository that forwards everything to `base`, except that `remember` always fails: for the tests
/// that prove a failing memory write never undoes a box.
public struct FailingRememberNamedProducts: NamedProductRepository {
    private let base: any NamedProductRepository

    public init(base: any NamedProductRepository) {
        self.base = base
    }

    public func namedProduct(gtin: String) async throws -> NamedProduct? { try await base.namedProduct(gtin: gtin) }

    public func remember(gtin: String, name: String, category: CategoryID, categoryOverridden: Bool) async throws {
        throw RepositoryError.notFound
    }

    public func observeSummaries() -> AsyncThrowingStream<[NamedProductSummary], any Error> { base.observeSummaries() }

    public func rename(id: NamedProduct.ID, to name: String) async throws { try await base.rename(id: id, to: name) }

    public func setCategory(id: NamedProduct.ID, _ category: CategoryID, overridden: Bool) async throws {
        try await base.setCategory(id: id, category, overridden: overridden)
    }

    public func forget(id: NamedProduct.ID) async throws { try await base.forget(id: id) }

    public func undoForget(id: NamedProduct.ID) async throws { try await base.undoForget(id: id) }

    public func backfillPending() async throws -> Bool { try await base.backfillPending() }

    public func completeBackfill(_ products: [NamedProduct]) async throws { try await base.completeBackfill(products) }
}
