/// A product known to the reference data (BDPM), by GTIN.
public struct ReferenceProduct: Hashable, Sendable {
    public let name: String
    public let category: CategoryID?
    /// BDPM's 8-digit identifier of the medicine. `nil` when the reference does not hold it (a withdrawn
    /// presentation carried over from an older database).
    public let cis: String?
    /// The presentation left the BDPM: still known by name, but it has no official page to show.
    public let isWithdrawn: Bool

    public init(name: String, category: CategoryID? = nil, cis: String? = nil, isWithdrawn: Bool = false) {
        self.name = name
        self.category = category
        self.cis = cis
        self.isWithdrawn = isWithdrawn
    }
}

public protocol ProductLookup: Sendable {
    /// `nil` = unknown product. Never a partial or guessed match.
    func product(gtin: String) async throws -> ReferenceProduct?
}
