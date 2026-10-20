import MediCabinetCore

/// UI tests without a product list: every product is unknown and the app asks for its name. The app itself
/// looks products up in the bundled BDPM data (`AppEnvironment.reference`).
struct UnknownProductLookup: ProductLookup {
    func product(gtin: String) async throws -> ReferenceProduct? { nil }
}

/// UI tests: a fixed list of known products.
struct FixedProductLookup: ProductLookup {
    let products: [String: ReferenceProduct]

    func product(gtin: String) async throws -> ReferenceProduct? { products[gtin] }
}
