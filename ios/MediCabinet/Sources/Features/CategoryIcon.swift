import MediCabinetCore

/// The SF Symbol of a category card. Decorative: the label carries the meaning, so views hide the icon from
/// VoiceOver. The same concepts as the Android icons (`CategoryIcons`).
enum CategoryIcon {
    private static let symbols: [String: String] = [
        "pain-fever": "thermometer.medium",
        "cold-ent": "nose",
        "digestion": "fork.knife",
        "allergy": "allergens",
        "skin-wound": "bandage",
        "eyes": "eye",
        "muscles-joints": "figure.walk",
        "mouth-teeth": "mouth",
        "vitamins-minerals": "pills",
        "first-aid": "cross.case",
        "uncategorized": "tray"
    ]

    private static let otherSymbol = "square.grid.2x2"

    /// An identifier this app version does not know gets the `other` icon, like its label.
    static func symbol(for id: CategoryID) -> String {
        symbols[Taxonomy.displayed(id).rawValue] ?? otherSymbol
    }
}
