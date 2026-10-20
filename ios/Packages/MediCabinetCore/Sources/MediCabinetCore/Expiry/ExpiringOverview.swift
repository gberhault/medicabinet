import Foundation

/// The order of names in the Expiring view: the same folding and scalar comparison as the Cabinet list and the
/// search (`TextNormalizer`), so a name sorts identically everywhere and on both platforms. "Éfferalgan" sorts next
/// to "efferalgan".
public enum NameOrder {
    public static func folded(_ name: String) -> String {
        TextNormalizer.normalize(name)
    }

    /// `true` when `lhs` sorts before `rhs`: folded name first, then the lower-case id.
    public static func precedes(_ lhs: Box, _ rhs: Box) -> Bool {
        precedes(SortKey(lhs), SortKey(rhs))
    }

    struct SortKey {
        let name: String
        let id: String

        init(_ box: Box) {
            name = folded(box.product.name)
            id = box.id.uuidString.lowercased()
        }
    }

    static func precedes(_ lhs: SortKey, _ rhs: SortKey) -> Bool {
        let byName = TextNormalizer.compare(lhs.name, rhs.name)
        return byName != 0 ? byName < 0 : TextNormalizer.compare(lhs.id, rhs.id) < 0
    }
}

public struct ExpiringEntry: Hashable, Sendable {
    public let box: Box
    public let assessment: ExpiryAssessment

    public init(box: Box, assessment: ExpiryAssessment) {
        self.box = box
        self.assessment = assessment
    }
}

/// What the Expiring view shows: expired, expiring-soon and unknown-expiry boxes in display order.
/// OK boxes and boxes no longer in the cabinet are never listed.
public struct ExpiringOverview: Hashable, Sendable {
    /// Oldest expiry first.
    public let expired: [ExpiringEntry]
    /// Soonest first.
    public let expiringSoon: [ExpiringEntry]
    /// By folded name, then id.
    public let unknown: [ExpiringEntry]

    /// The tab badge: boxes that need attention. Unknown boxes are not counted.
    public var attentionCount: Int { expired.count + expiringSoon.count }

    /// Nothing to show in any section.
    public var isEmpty: Bool { expired.isEmpty && expiringSoon.isEmpty && unknown.isEmpty }

    public static func make(boxes: [Box], today: CalendarDay, window: ExpiryWindow) -> ExpiringOverview {
        typealias Keyed = (entry: ExpiringEntry, key: NameOrder.SortKey)
        var expired: [Keyed] = []
        var soon: [Keyed] = []
        var unknown: [Keyed] = []
        for box in boxes where box.isInCabinet {
            let assessment = ExpiryRule.assess(box.expiry, today: today, window: window)
            let keyed = (ExpiringEntry(box: box, assessment: assessment), NameOrder.SortKey(box))
            switch assessment.status {
            case .expired: expired.append(keyed)
            case .expiringSoon: soon.append(keyed)
            case .unknown: unknown.append(keyed)
            case .ok: break
            }
        }
        // Equal days left means the same last valid day, so "oldest first" and "soonest first" are both
        // ascending days left.
        func byDaysThenName(_ lhs: Keyed, _ rhs: Keyed) -> Bool {
            let lhsDays = lhs.entry.assessment.daysLeft ?? 0
            let rhsDays = rhs.entry.assessment.daysLeft ?? 0
            return lhsDays != rhsDays ? lhsDays < rhsDays : NameOrder.precedes(lhs.key, rhs.key)
        }
        return ExpiringOverview(
            expired: expired.sorted(by: byDaysThenName).map(\.entry),
            expiringSoon: soon.sorted(by: byDaysThenName).map(\.entry),
            unknown: unknown.sorted { NameOrder.precedes($0.key, $1.key) }.map(\.entry)
        )
    }
}
