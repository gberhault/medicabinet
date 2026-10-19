import MediCabinetCore

/// A reference data source held in memory. Products are keyed by CIP13; lookups go through `CIP13.from`, like the
/// real implementations.
public struct InMemoryReferenceData: ReferenceDataSource {
    private let products: [String: ReferenceProduct]
    private let currentStatus: ReferenceStatus

    public init(products: [String: ReferenceProduct] = [:], status: ReferenceStatus) {
        self.products = products
        self.currentStatus = status
    }

    /// Available, dated, with the given products.
    public init(products: [String: ReferenceProduct] = [:], dataDate: ReferenceDataDate) {
        self.init(products: products, status: .available(dataDate: dataDate))
    }

    public func product(gtin: String) async throws -> ReferenceProduct? {
        guard case .available = currentStatus, let cip13 = CIP13.from(gtin: gtin) else { return nil }
        return products[cip13]
    }

    public func status() async -> ReferenceStatus {
        currentStatus
    }
}
