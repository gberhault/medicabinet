import MediCabinetCore

/// UI tests: the reference data is available and holds a fixed list of products (none by default, so the app asks for
/// every name). The app itself uses the bundled BDPM data (`AppEnvironment.reference`).
struct FixedProductLookup: ReferenceDataSource {
    /// Any real calendar day: the date is not shown on the scan screen.
    private static let dataDate = ReferenceDataDate(iso: "2026-01-01")

    let products: [String: ReferenceProduct]

    func product(gtin: String) async throws -> ReferenceProduct? { products[gtin] }

    func status() async -> ReferenceStatus {
        Self.dataDate.map { .available(dataDate: $0) } ?? .unavailable(.unreadable)
    }
}

/// UI tests without a product list: every product is unknown and the app asks for its name.
struct UnknownProductLookup: ReferenceDataSource {
    private let fixed = FixedProductLookup(products: [:])

    func product(gtin: String) async throws -> ReferenceProduct? { nil }

    func status() async -> ReferenceStatus { await fixed.status() }
}
