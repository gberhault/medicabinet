import Foundation

/// User-facing text of the Cabinet screens that needs formatting: plural counts and the box code.
enum CabinetText {
    /// "3 boxes" in the header: the boxes matching the search and filters.
    static func boxCount(_ count: Int, bundle: Bundle = .main, locale: Locale = .current) -> String {
        plural("cabinet.count", count, bundle: bundle, locale: locale)
    }

    /// "1 box" / "3 boxes" in a product row.
    static func rowBoxes(_ count: Int, bundle: Bundle = .main, locale: Locale = .current) -> String {
        plural("cabinet.row.boxes", count, bundle: bundle, locale: locale)
    }

    /// The code shown on a box: the 13 digits (CIP13 / EAN-13) when the GTIN-14 starts with `0`, else the GTIN-14;
    /// "No code" when the box has none.
    static func code(gtin: String?, bundle: Bundle = .main) -> String {
        guard let gtin, !gtin.isEmpty else {
            return bundle.localizedString(forKey: "box.noCode", value: nil, table: nil)
        }
        return gtin.hasPrefix("0") ? String(gtin.dropFirst()) : gtin
    }

    /// "Doliprane: used up", "Doliprane: thrown away" or "Doliprane: deleted" (the undo messages).
    static func undoMessage(_ key: String, name: String, bundle: Bundle = .main) -> String {
        String(format: bundle.localizedString(forKey: key, value: nil, table: nil), name)
    }

    private static func plural(_ key: String, _ count: Int, bundle: Bundle, locale: Locale) -> String {
        String(format: bundle.localizedString(forKey: key, value: nil, table: nil), locale: locale, count)
    }
}
