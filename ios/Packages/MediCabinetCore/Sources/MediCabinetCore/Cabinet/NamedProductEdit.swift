public enum NamedProductEditError: Error, Equatable, Sendable {
    /// The name is empty once trimmed.
    case emptyName
    /// The name is longer than `ScanIntake.maxNameLength` code points.
    case nameTooLong
    /// The category is not one the user may pick (`uncategorized`, or an identifier this version does not know).
    case categoryNotSelectable

    /// Platform-neutral name, shared with the Android app.
    public var kind: String {
        switch self {
        case .emptyName: "emptyName"
        case .nameTooLong: "nameTooLong"
        case .categoryNotSelectable: "categoryNotSelectable"
        }
    }
}

/// What the Settings form can change on a named product: its name and category. The code is what identifies the
/// product and stays as it is.
public struct NamedProductEdit: Hashable, Sendable {
    private enum CategoryChoice: Hashable, Sendable {
        case chosen(CategoryID)
        /// **Automatic category**: for a code the reference does not name, `uncategorized` with the flag off.
        case automatic
    }

    public var name: String
    private let original: NamedProduct
    private var categoryChoice: CategoryChoice?

    /// The form's starting point: the product as it is.
    public init(from product: NamedProduct) {
        original = product
        name = product.name
    }

    /// The user picks a category from the taxonomy. A category the user may not pick is refused and changes nothing.
    public mutating func chooseCategory(_ category: CategoryID) throws(NamedProductEditError) {
        guard Taxonomy.userSelectable.contains(category) else { throw .categoryNotSelectable }
        categoryChoice = .chosen(category)
    }

    /// **Automatic category**: `uncategorized`, with the flag off.
    public mutating func resetCategory() {
        categoryChoice = .automatic
    }

    /// Only what changed: the trimmed name when it differs, the category and its flag when they differ. Both `nil`
    /// = nothing to save.
    public func validated() throws(NamedProductEditError) -> (name: String?, category: (CategoryID, Bool)?) {
        let trimmed = TextNormalizer.trimmed(name)
        guard !trimmed.isEmpty else { throw .emptyName }
        guard trimmed.unicodeScalars.count <= ScanIntake.maxNameLength else { throw .nameTooLong }
        let newCategory: (CategoryID, Bool)? = switch categoryChoice {
        case .chosen(let category): (category, true)
        case .automatic: (.uncategorized, false)
        case nil: nil
        }
        let changedCategory = newCategory.flatMap { choice in
            choice.0 == original.category && choice.1 == original.categoryOverridden ? nil : choice
        }
        return (trimmed == original.name ? nil : trimmed, changedCategory)
    }
}
