/// What a scan knows about the product. `name == nil` means unknown: the app asks for a name.
public struct ResolvedProduct: Hashable, Sendable {
    public let name: String?
    public let category: CategoryID
    public let categoryOverridden: Bool

    public init(name: String?, category: CategoryID, categoryOverridden: Bool) {
        self.name = name
        self.category = category
        self.categoryOverridden = categoryOverridden
    }
}

public enum ProductResolver {
    /// For a code the reference knows: the reference name, and the category of the previous box when the user set
    /// it, else the reference category, else `uncategorized` (`named` is ignored).
    /// For a code it does not know: the name of the named product (`nil` = ask) and its category when the user set
    /// it, else `uncategorized`. The previous box no longer names such a code (#57).
    public static func resolve(reference: ReferenceProduct?, named: NamedProduct?, previousBox: Box?) -> ResolvedProduct {
        if let reference {
            if let previousBox, previousBox.categoryOverridden {
                return ResolvedProduct(name: reference.name, category: previousBox.category, categoryOverridden: true)
            }
            return ResolvedProduct(name: reference.name, category: reference.category ?? .uncategorized, categoryOverridden: false)
        }
        guard let named else { return ResolvedProduct(name: nil, category: .uncategorized, categoryOverridden: false) }
        if named.categoryOverridden {
            return ResolvedProduct(name: named.name, category: named.category, categoryOverridden: true)
        }
        return ResolvedProduct(name: named.name, category: .uncategorized, categoryOverridden: false)
    }
}
