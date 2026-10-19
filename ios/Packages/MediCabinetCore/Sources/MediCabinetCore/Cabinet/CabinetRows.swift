import Foundation

/// What the Cabinet list is narrowed to. All parts combine.
public struct CabinetFilter: Hashable, Sendable {
    /// Free text; matched against the row name (`TextNormalizer.matches`).
    public var query: String
    /// `nil` = all locations.
    public var locationID: Location.ID?
    /// `nil` = all statuses.
    public var status: ExpiryStatus?

    public init(query: String = "", locationID: Location.ID? = nil, status: ExpiryStatus? = nil) {
        self.query = query
        self.locationID = locationID
        self.status = status
    }

    public var isActive: Bool {
        !TextNormalizer.trimmed(query).isEmpty || locationID != nil || status != nil
    }
}

/// What makes two boxes "the same product" in the list.
public enum ProductKey: Hashable, Sendable {
    case gtin(String)
    /// Normalized name, for boxes without a code.
    case name(String)
}

/// How urgent an expiry status is: expired first, then expiring soon, then unknown, then ok — an unknown
/// expiry is never ranked as safe.
public enum ExpiryUrgency {
    public static func rank(_ status: ExpiryStatus) -> Int {
        switch status {
        case .expired: 0
        case .expiringSoon: 1
        case .unknown: 2
        case .ok: 3
        }
    }
}

public struct ProductRowID: Hashable, Sendable {
    public let category: CategoryID
    public let key: ProductKey
}

/// One row of the Cabinet list: the boxes in the cabinet that share a product key and a category.
public struct ProductRow: Identifiable, Hashable, Sendable {
    public let id: ProductRowID
    /// Name of the most recently created box of the row.
    public let name: String
    /// Only the boxes that passed the filters, earliest expiry first (unknown last).
    public let boxes: [Box]
    /// The most urgent status among `boxes`.
    public let status: ExpiryStatus
    /// `nil` when no box of the row has a known expiry.
    public let earliestExpiry: ExpiryDate?
}

public struct CategorySection: Identifiable, Hashable, Sendable {
    public let category: CategoryID
    public let rows: [ProductRow]

    public var id: CategoryID { category }
}

public struct CabinetSnapshot: Hashable, Sendable {
    public let sections: [CategorySection]
    /// Boxes shown (the sum over all rows).
    public let boxCount: Int

    public static let empty = CabinetSnapshot(sections: [], boxCount: 0)
}

/// Builds the Cabinet list from the boxes in the cabinet. Pure and synchronous; callers run it off the main
/// thread. The ordering rules are pinned by `fixtures/cabinet-rows.json`.
public enum CabinetRows {
    /// - Parameters:
    ///   - boxes: boxes in the cabinet (not removed, not deleted).
    ///   - categoryOrder: the taxonomy's order; categories not listed follow, by key; `uncategorized` is last.
    ///   - status: the expiry status of a box (the expiry-status rule with today's date).
    public static func build(
        boxes: [Box],
        filter: CabinetFilter,
        categoryOrder: [CategoryID],
        status: (Box) -> ExpiryStatus
    ) -> CabinetSnapshot {
        var groups: [ProductRowID: [(box: Box, status: ExpiryStatus)]] = [:]
        for box in boxes {
            if let locationID = filter.locationID, box.locationID != locationID { continue }
            let boxStatus = status(box)
            if let wanted = filter.status, boxStatus != wanted { continue }
            let id = ProductRowID(category: box.category, key: productKey(of: box))
            groups[id, default: []].append((box, boxStatus))
        }

        var entries: [Entry] = []
        for (id, group) in groups {
            guard let entry = entry(id: id, group: group, query: filter.query) else { continue }
            entries.append(entry)
        }

        let rank = categoryRank(order: categoryOrder)
        var rowsByCategory: [CategoryID: [Entry]] = [:]
        for entry in entries { rowsByCategory[entry.row.id.category, default: []].append(entry) }
        let sections = rowsByCategory.keys
            .sorted { rank($0) < rank($1) }
            .map { category in
                CategorySection(category: category, rows: (rowsByCategory[category] ?? []).sorted(by: rowOrder).map(\.row))
            }
        return CabinetSnapshot(sections: sections, boxCount: entries.reduce(0) { $0 + $1.row.boxes.count })
    }

    // MARK: - Private

    private struct Entry {
        let row: ProductRow
        let normalizedName: String
        let earliestKey: String?
        let firstBoxID: String
    }

    private struct CategoryRank: Comparable {
        /// 0 = listed in the taxonomy, 1 = unlisted, 2 = uncategorized.
        let bucket: Int
        let index: Int
        let key: String

        static func < (lhs: CategoryRank, rhs: CategoryRank) -> Bool {
            if lhs.bucket != rhs.bucket { return lhs.bucket < rhs.bucket }
            if lhs.index != rhs.index { return lhs.index < rhs.index }
            return TextNormalizer.compare(lhs.key, rhs.key) < 0
        }
    }

    private static func productKey(of box: Box) -> ProductKey {
        if let gtin = box.product.gtin { return .gtin(gtin) }
        return .name(TextNormalizer.normalize(box.product.name))
    }

    private static func entry(
        id: ProductRowID, group: [(box: Box, status: ExpiryStatus)], query: String
    ) -> Entry? {
        let newest = group.map(\.box).max { isOlder($0, than: $1) } ?? group[0].box
        let normalizedName = TextNormalizer.normalize(newest.product.name)
        guard TextNormalizer.matches(normalizedText: normalizedName, query: query) else { return nil }

        let ordered = group.sorted { boxOrder($0.box, $1.box) }
        let mostUrgent = ordered.map(\.status).min { ExpiryUrgency.rank($0) < ExpiryUrgency.rank($1) } ?? .unknown
        let earliest = ordered.compactMap(\.box.expiry).min()
        let row = ProductRow(
            id: id, name: newest.product.name, boxes: ordered.map(\.box), status: mostUrgent, earliestExpiry: earliest)
        return Entry(
            row: row, normalizedName: normalizedName, earliestKey: earliest?.sortKey,
            firstBoxID: idKey(ordered[0].box))
    }

    /// Lower-case canonical string: ordered the same way as the JVM twin (never `UUID.compareTo`).
    private static func idKey(_ box: Box) -> String {
        box.id.uuidString.lowercased()
    }

    private static func isOlder(_ lhs: Box, than rhs: Box) -> Bool {
        if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
        return TextNormalizer.compare(idKey(lhs), idKey(rhs)) < 0
    }

    /// Earliest expiry first, unknown last, then creation order, then id.
    private static func boxOrder(_ lhs: Box, _ rhs: Box) -> Bool {
        switch (lhs.expiry?.sortKey, rhs.expiry?.sortKey) {
        case let (left?, right?) where left != right: return left < right
        case (nil, _?): return false
        case (_?, nil): return true
        default: return isOlder(lhs, than: rhs)
        }
    }

    private static func rowOrder(_ lhs: Entry, _ rhs: Entry) -> Bool {
        let leftRank = ExpiryUrgency.rank(lhs.row.status)
        let rightRank = ExpiryUrgency.rank(rhs.row.status)
        if leftRank != rightRank { return leftRank < rightRank }
        switch (lhs.earliestKey, rhs.earliestKey) {
        case let (left?, right?) where left != right: return left < right
        case (nil, _?): return false
        case (_?, nil): return true
        default: break
        }
        let byName = TextNormalizer.compare(lhs.normalizedName, rhs.normalizedName)
        if byName != 0 { return byName < 0 }
        return TextNormalizer.compare(lhs.firstBoxID, rhs.firstBoxID) < 0
    }

    private static func categoryRank(order: [CategoryID]) -> (CategoryID) -> CategoryRank {
        let positions = Dictionary(
            order.enumerated().filter { $0.element != .uncategorized }.map { ($0.element, $0.offset) },
            uniquingKeysWith: { first, _ in first })
        return { category in
            if category == .uncategorized { return CategoryRank(bucket: 2, index: 0, key: category.rawValue) }
            if let index = positions[category] { return CategoryRank(bucket: 0, index: index, key: category.rawValue) }
            return CategoryRank(bucket: 1, index: 0, key: category.rawValue)
        }
    }
}
