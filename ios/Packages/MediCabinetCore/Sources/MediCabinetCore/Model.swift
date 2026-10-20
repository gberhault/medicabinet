import Foundation

/// Stable key of a category (e.g. `pain-fever`); the twelve categories are in `Taxonomy`.
public struct CategoryID: RawRepresentable, Hashable, Sendable, Codable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static let uncategorized = CategoryID(rawValue: "uncategorized")
}

/// What a box is. The name is a snapshot, so a box stays readable if reference data changes.
public struct ProductRef: Hashable, Sendable {
    /// 14 digits when known.
    public var gtin: String?
    public var name: String

    public init(gtin: String? = nil, name: String) {
        self.gtin = gtin
        self.name = name
    }
}

public enum ExpiryStatus: String, Hashable, Sendable, CaseIterable {
    case expired
    case expiringSoon
    case ok
    /// No trustworthy expiry date is known. Never presented as OK.
    case unknown
}

public enum RemovalReason: String, Hashable, Sendable, Codable, CaseIterable {
    case usedUp
    case thrownAway
}

public struct Location: Identifiable, Hashable, Sendable {
    public let id: UUID
    public var name: String
    public var isDefault: Bool
    public var sortOrder: Int
    public let createdAt: Date
    public var updatedAt: Date

    public init(
        id: UUID = UUID(),
        name: String,
        isDefault: Bool = false,
        sortOrder: Int = 0,
        createdAt: Date,
        updatedAt: Date? = nil
    ) {
        self.id = id
        self.name = name
        self.isDefault = isDefault
        self.sortOrder = sortOrder
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
    }
}

/// One physical box in the cabinet. Quantities are counted in boxes only.
public struct Box: Identifiable, Hashable, Sendable {
    public struct Removal: Hashable, Sendable {
        public var reason: RemovalReason
        public var date: Date

        public init(reason: RemovalReason, date: Date) {
            self.reason = reason
            self.date = date
        }
    }

    public let id: UUID
    public var product: ProductRef
    public var category: CategoryID
    public var categoryOverridden: Bool
    public var locationID: Location.ID
    /// `nil` = unknown. Never defaulted.
    public var expiry: ExpiryDate?
    public var lot: String?
    public var serial: String?
    public var isOpened: Bool
    public let createdAt: Date
    public var updatedAt: Date
    /// `nil` = still in the cabinet.
    public var removal: Removal?

    public init(
        id: UUID = UUID(),
        product: ProductRef,
        category: CategoryID = .uncategorized,
        categoryOverridden: Bool = false,
        locationID: Location.ID,
        expiry: ExpiryDate? = nil,
        lot: String? = nil,
        serial: String? = nil,
        isOpened: Bool = false,
        createdAt: Date,
        updatedAt: Date? = nil,
        removal: Removal? = nil
    ) {
        self.id = id
        self.product = product
        self.category = category
        self.categoryOverridden = categoryOverridden
        self.locationID = locationID
        self.expiry = expiry
        self.lot = lot
        self.serial = serial
        self.isOpened = isOpened
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
        self.removal = removal
    }

    public var isInCabinet: Bool { removal == nil }
}

/// What the household taught the app about one code (GTIN) that the bundled reference database does not name:
/// the name and category it gave. At most one live named product per GTIN.
public struct NamedProduct: Identifiable, Hashable, Sendable {
    public let id: UUID
    /// 14 digits.
    public let gtin: String
    public var name: String
    public var category: CategoryID
    /// The category was chosen by the user (else it is `uncategorized` and the flag is off).
    public var categoryOverridden: Bool
    public let createdAt: Date
    public var updatedAt: Date

    public init(
        id: UUID = UUID(), gtin: String, name: String, category: CategoryID = .uncategorized,
        categoryOverridden: Bool = false, createdAt: Date, updatedAt: Date? = nil
    ) {
        self.id = id
        self.gtin = gtin
        self.name = name
        self.category = category
        self.categoryOverridden = categoryOverridden
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
    }
}

/// One row of the Settings list: a named product and how many of its boxes are in the cabinet.
public struct NamedProductSummary: Hashable, Sendable, Identifiable {
    public let product: NamedProduct
    public let boxesInCabinet: Int

    public var id: NamedProduct.ID { product.id }

    public init(product: NamedProduct, boxesInCabinet: Int) {
        self.product = product
        self.boxesInCabinet = boxesInCabinet
    }
}

public enum NamedProductOrder {
    /// By name compared as normalized text, then by GTIN: the order of the Settings list on both platforms.
    public static func sorted(_ summaries: [NamedProductSummary]) -> [NamedProductSummary] {
        summaries.map { (TextNormalizer.normalize($0.product.name), $0) }
            .sorted { lhs, rhs in
                let byName = TextNormalizer.compare(lhs.0, rhs.0)
                if byName != 0 { return byName < 0 }
                return TextNormalizer.compare(lhs.1.product.gtin, rhs.1.product.gtin) < 0
            }
            .map(\.1)
    }
}
