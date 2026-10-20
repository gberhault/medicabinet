import Foundation
import MediCabinetCore

/// User-facing text of expiry tracking that needs formatting: the window choices, dates in the user's locale, plural
/// counts. Always from the String Catalog; never built by concatenation.
enum ExpiryText {
    /// "60 days" in the window picker.
    static func windowOption(_ window: ExpiryWindow, bundle: Bundle = .main) -> String {
        String(format: bundle.localizedString(forKey: "settings.expiryWindow.option", value: nil, table: nil), window.days)
    }
}
