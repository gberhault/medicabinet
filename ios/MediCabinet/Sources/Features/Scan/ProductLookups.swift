import MediCabinetCore

/// Until the BDPM reference data is bound (epic #7, `ProductLookup` from #11), every product is unknown and
/// the app asks for its name.
struct UnknownProductLookup: ProductLookup {
    func product(gtin: String) async throws -> ReferenceProduct? { nil }
}

/// UI tests: a fixed list of known products.
struct FixedProductLookup: ProductLookup {
    let products: [String: ReferenceProduct]

    func product(gtin: String) async throws -> ReferenceProduct? { products[gtin] }
}
