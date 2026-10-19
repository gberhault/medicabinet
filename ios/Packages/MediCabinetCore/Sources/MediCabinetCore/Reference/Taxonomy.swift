/// The household categories, in display order. The identifiers are shared with the Android app and the reference-data
/// build (`fixtures/categories.json`); the labels are localized by the apps.
extension CategoryID {
    public static let painFever = CategoryID(rawValue: "pain-fever")
    public static let coldENT = CategoryID(rawValue: "cold-ent")
    public static let digestion = CategoryID(rawValue: "digestion")
    public static let allergy = CategoryID(rawValue: "allergy")
    public static let skinWound = CategoryID(rawValue: "skin-wound")
    public static let eyes = CategoryID(rawValue: "eyes")
    public static let musclesJoints = CategoryID(rawValue: "muscles-joints")
    public static let mouthTeeth = CategoryID(rawValue: "mouth-teeth")
    public static let vitaminsMinerals = CategoryID(rawValue: "vitamins-minerals")
    /// Never assigned automatically (BDPM holds no dressings or devices): the user picks it.
    public static let firstAid = CategoryID(rawValue: "first-aid")
    public static let other = CategoryID(rawValue: "other")
}

public enum Taxonomy {
    /// All twelve, in display order.
    public static let ordered: [CategoryID] = [
        .painFever, .coldENT, .digestion, .allergy, .skinWound, .eyes, .musclesJoints, .mouthTeeth,
        .vitaminsMinerals, .firstAid, .other, .uncategorized
    ]

    /// What a user may pick: everything but `uncategorized`, which means "nobody chose".
    public static let userSelectable: [CategoryID] = ordered.filter { $0 != .uncategorized }

    /// The category to show for a stored value. An identifier this app version does not know (for instance from a
    /// newer app's export) is shown as `other`; the stored value itself is never rewritten.
    public static func displayed(_ id: CategoryID) -> CategoryID {
        ordered.contains(id) ? id : .other
    }
}
