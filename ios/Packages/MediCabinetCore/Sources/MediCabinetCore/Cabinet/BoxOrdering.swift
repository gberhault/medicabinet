/// The box orderings shared by the Cabinet list and removal by scan; identical on Android (`BoxOrdering`).
enum BoxOrdering {
    /// Lower-case canonical string: ordered the same way on both platforms (never `UUID.compareTo`).
    static func idKey(_ box: Box) -> String {
        box.id.uuidString.lowercased()
    }

    /// Oldest first: creation time, then id.
    static func isOlder(_ lhs: Box, than rhs: Box) -> Bool {
        if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
        return TextNormalizer.compare(idKey(lhs), idKey(rhs)) < 0
    }

    /// Earliest expiry first (month precision = last day of the month), unknown last, then creation order.
    static func expiryThenCreation(_ lhs: Box, _ rhs: Box) -> Bool {
        switch (lhs.expiry?.sortKey, rhs.expiry?.sortKey) {
        case let (left?, right?) where left != right: return left < right
        case (nil, _?): return false
        case (_?, nil): return true
        default: return isOlder(lhs, than: rhs)
        }
    }
}
