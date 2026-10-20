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
    /// The name is the reference name, else the previous box's. The category is the previous box's when
    /// the user set it, else the reference category, else `uncategorized`.
    public static func resolve(reference: ReferenceProduct?, previousBox: Box?) -> ResolvedProduct {
        let name = reference?.name ?? previousBox?.product.name
        if let previousBox, previousBox.categoryOverridden {
            return ResolvedProduct(name: name, category: previousBox.category, categoryOverridden: true)
        }
        return ResolvedProduct(name: name, category: reference?.category ?? .uncategorized, categoryOverridden: false)
    }
}
