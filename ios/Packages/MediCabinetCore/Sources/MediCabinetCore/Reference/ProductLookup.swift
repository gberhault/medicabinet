/// A product known to the reference data (BDPM), by GTIN.
public struct ReferenceProduct: Hashable, Sendable {
    public let name: String
    public let category: CategoryID?

    public init(name: String, category: CategoryID? = nil) {
        self.name = name
        self.category = category
    }
}

public protocol ProductLookup: Sendable {
    /// `nil` = unknown product. Never a partial or guessed match.
    func product(gtin: String) async throws -> ReferenceProduct?
}
