/// What the app remembers about the codes the reference database does not name: reads the named product of a code
/// (and, from `record`, writes it) so that a scan resolves a name without the household typing it twice.
public struct ProductMemory: Sendable {
    private let named: any NamedProductRepository
    private let lookup: any ProductLookup
    /// `false` when the reference data cannot answer: every code then looks unknown, and nothing may be remembered.
    private let isReferenceAvailable: @Sendable () async -> Bool

    public init(named: any NamedProductRepository, reference: any ReferenceDataSource) {
        self.named = named
        lookup = reference
        isReferenceAvailable = {
            if case .available = await reference.status() { return true }
            return false
        }
    }

    /// For a lookup that has no status of its own (a test double): it is taken as available.
    public init(named: any NamedProductRepository, lookup: any ProductLookup) {
        self.named = named
        self.lookup = lookup
        isReferenceAvailable = { true }
    }

    /// The reference name, else the named product's, else `nil` (ask). A failing reference lookup leaves the code
    /// unknown instead of stopping intake. `previousBox` only carries a user category over for a known code.
    public func resolve(gtin: String, previousBox: Box?) async throws -> ResolvedProduct {
        let reference = try? await lookup.product(gtin: gtin)
        var product: NamedProduct?
        if reference == nil { product = try await named.namedProduct(gtin: gtin) }
        return ProductResolver.resolve(reference: reference, named: product, previousBox: previousBox)
    }

    /// Remembers what `event` taught about the code, when `MemoryPolicy` says so. Throws only when the write fails:
    /// the caller has already saved the box and must not undo it (losing one memory update must not lose a box).
    public func record(_ event: MemoryEvent) async throws {
        // Saves that cannot remember (no code, nothing changed) cost no reference lookup and no read.
        guard MemoryPolicy.decide(event, codeKnown: false, current: nil) != .none,
              let gtin = event.after.product.gtin else { return }
        let codeKnown = await isKnown(gtin: gtin)
        var current: NamedProduct?
        if codeKnown == false { current = try await named.namedProduct(gtin: gtin) }
        guard case let .remember(name, category, overridden) = MemoryPolicy.decide(event, codeKnown: codeKnown, current: current)
        else { return }
        try await named.remember(gtin: gtin, name: name, category: category, categoryOverridden: overridden)
    }

    /// `nil` = cannot tell: the reference is unavailable (every code would look unknown) or the lookup failed.
    private func isKnown(gtin: String) async -> Bool? {
        guard await isReferenceAvailable() else { return nil }
        do {
            return try await lookup.product(gtin: gtin) != nil
        } catch {
            return nil
        }
    }
}
