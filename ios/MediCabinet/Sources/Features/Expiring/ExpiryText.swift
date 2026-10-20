import Foundation
import MediCabinetCore

/// User-facing text of expiry tracking that needs formatting: the window choices, dates in the user's locale, plural
/// counts. Always from the String Catalog; never built by concatenation.
enum ExpiryText {
    /// "60 days" in the window picker.
    static func windowOption(_ window: ExpiryWindow, bundle: Bundle = .main) -> String {
        String(format: bundle.localizedString(forKey: "settings.expiryWindow.option", value: nil, table: nil), window.days)
    }

    /// "Expires: 15 March 2027", or "Expires: March 2027" for a month-precise date: the month and the year, never an
    /// invented day. `nil` for an unknown expiry (the status already says so).
    static func expiryLine(_ expiry: ExpiryDate?, locale: Locale = .current, bundle: Bundle = .main) -> String? {
        guard let expiry else { return nil }
        let gregorian = Calendar(identifier: .gregorian)
        let components = DateComponents(
            calendar: gregorian, timeZone: .gmt, year: expiry.year, month: expiry.month, day: expiry.day ?? 1
        )
        // Cannot fail for a valid ExpiryDate.
        guard let date = components.date else { return nil }
        let style = Date.FormatStyle(locale: locale, calendar: gregorian, timeZone: .gmt)
        let text = expiry.day != nil ? date.formatted(style.day().month(.wide).year()) : date.formatted(style.month(.wide).year())
        return String(format: bundle.localizedString(forKey: "expiring.row.expiry", value: nil, table: nil), text)
    }

    /// "Nothing expires in the next 60 days."
    static func emptyMessage(window: ExpiryWindow, bundle: Bundle = .main) -> String {
        String(format: bundle.localizedString(forKey: "expiring.empty.message", value: nil, table: nil), window.days)
    }

    /// "3 boxes need attention": the tab badge as VoiceOver reads it.
    static func badgeAccessibility(_ count: Int, bundle: Bundle = .main, locale: Locale = .current) -> String {
        String(
            format: bundle.localizedString(forKey: "expiring.badge.accessibility", value: nil, table: nil),
            locale: locale, count
        )
    }

    /// What VoiceOver reads for a row: name, status, expiry line and location. An unknown expiry has no expiry line:
    /// its status says so, and it is not read twice.
    static func rowAccessibility(
        name: String, status: String, expiryLine: String?, location: String, bundle: Bundle = .main
    ) -> String {
        let format = bundle.localizedString(forKey: "expiring.row.accessibility", value: nil, table: nil)
        let text = String(format: format, name, status, expiryLine ?? "", location)
        return expiryLine == nil ? text.replacingOccurrences(of: ", , ", with: ", ") : text
    }
}
