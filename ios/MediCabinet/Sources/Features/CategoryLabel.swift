import Foundation
import MediCabinetCore

/// The localized name of a category. Labels describe a body area or a kind of product, never an indication.
enum CategoryLabel {
    /// String catalog key of a category: `category.` + the identifier with `-` written `_` (`category.pain_fever`).
    /// An identifier this app version does not know is shown as `other` (the stored value is left alone).
    static func key(for id: CategoryID) -> String {
        "category." + Taxonomy.displayed(id).rawValue.replacingOccurrences(of: "-", with: "_")
    }

    /// `bundle` is where strings are looked up; tests pass a `fr.lproj` / `en.lproj` bundle to check each language.
    static func text(for id: CategoryID, bundle: Bundle = .main) -> String {
        bundle.localizedString(forKey: key(for: id), value: nil, table: nil)
    }
}
