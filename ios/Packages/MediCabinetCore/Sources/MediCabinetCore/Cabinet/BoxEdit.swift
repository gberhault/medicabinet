public enum BoxEditError: Error, Equatable, Sendable {
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

/// A change of the box's category (B4). Absent = the category stays as it is.
public enum CategoryChange: Hashable, Sendable {
    /// The user picked it: the box is marked `categoryOverridden`, so the classifier never moves it again.
    case chosen(CategoryID)
    /// **Automatic category**: the classifier's category (looked up by the caller) and the box is no longer
    /// `categoryOverridden`.
    case automatic(CategoryID)
}

/// What the box edit form can change: the name, the expiry, the location and the category. The code (GTIN), lot
/// and serial are what the box says and stay as scanned.
public struct BoxEdit: Hashable, Sendable {
    public var name: String
    /// `nil` = unknown. Set only by an explicit choice (**Date not printed**), never filled in or guessed.
    public var expiry: ExpiryDate?
    public var locationID: Location.ID
    public private(set) var categoryChange: CategoryChange?

    public init(name: String, expiry: ExpiryDate?, locationID: Location.ID) {
        self.name = name
        self.expiry = expiry
        self.locationID = locationID
    }

    /// The form's starting point: the box as it is, an unknown expiry staying unknown.
    public init(from box: Box) {
        self.init(name: box.product.name, expiry: box.expiry, locationID: box.locationID)
    }

    /// The user picks a category from the taxonomy. A category the user may not pick is refused and changes nothing.
    public mutating func chooseCategory(_ category: CategoryID) throws(BoxEditError) {
        guard Taxonomy.userSelectable.contains(category) else { throw .categoryNotSelectable }
        categoryChange = .chosen(category)
    }

    /// **Automatic category**: gives the box back to the classifier, whose answer is `automatic` (the reference
    /// category of its GTIN, else `uncategorized`).
    public mutating func resetCategory(to automatic: CategoryID) {
        categoryChange = .automatic(automatic)
    }

    /// The box with the edit applied. `updatedAt` is left to the repository, which stamps it on `update`.
    public func applied(to box: Box) throws(BoxEditError) -> Box {
        let trimmed = TextNormalizer.trimmed(name)
        guard !trimmed.isEmpty else { throw .emptyName }
        guard trimmed.unicodeScalars.count <= ScanIntake.maxNameLength else { throw .nameTooLong }
        var edited = box
        edited.product.name = trimmed
        edited.expiry = expiry
        edited.locationID = locationID
        switch categoryChange {
        case .chosen(let category):
            edited.category = category
            edited.categoryOverridden = true
        case .automatic(let category):
            edited.category = category
            edited.categoryOverridden = false
        case nil:
            break
        }
        return edited
    }
}
