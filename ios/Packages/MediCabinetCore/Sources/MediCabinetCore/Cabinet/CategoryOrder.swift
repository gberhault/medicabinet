/// The order of the category cards in the Cabinet: A–Z by the label the app shows, then *Other*, then
/// *Uncategorized*. Labels are compared as `TextNormalizer` text by code point — not by locale collation, which
/// can differ between platforms and OS versions. Pinned by `fixtures/category-order.json`; the Kotlin twin is
/// `CategoryOrder`.
public enum CategoryOrder {
    /// - Parameter labels: the localized label of each category to order.
    /// - Returns: the ids of `labels`, A–Z by normalized label (ties by raw id), `other` then `uncategorized` last.
    public static func alphabetical(labels: [CategoryID: String]) -> [CategoryID] {
        let keyed = labels.map { (id: $0.key, label: TextNormalizer.normalize($0.value)) }
        let regular = keyed.filter { $0.id != .other && $0.id != .uncategorized }
        let sorted = regular.sorted { lhs, rhs in
            let byLabel = TextNormalizer.compare(lhs.label, rhs.label)
            if byLabel != 0 { return byLabel < 0 }
            return TextNormalizer.compare(lhs.id.rawValue, rhs.id.rawValue) < 0
        }
        var result = sorted.map(\.id)
        if labels[.other] != nil { result.append(.other) }
        if labels[.uncategorized] != nil { result.append(.uncategorized) }
        return result
    }
}
