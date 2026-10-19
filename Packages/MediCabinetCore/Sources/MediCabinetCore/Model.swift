import Foundation

/// Stable key of a category (e.g. `pain-fever`); the taxonomy itself arrives with the
/// reference-data epic.
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
