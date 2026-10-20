import Foundation
import MediCabinetCore

/// User-facing text for the scan screens: always from the String Catalog, dates in the user's locale.
enum ScanText {
    /// "Expires 15 March 2027", "Expires end of March 2027" (month precision) or "Expiry unknown".
    static func expiry(_ expiry: ExpiryDate?, locale: Locale = .current, bundle: Bundle = .main) -> String {
        guard let expiry else { return bundle.localizedString(forKey: "scan.added.expiryUnknown", value: nil, table: nil) }
        let gregorian = Calendar(identifier: .gregorian)
        let components = DateComponents(
            calendar: gregorian, timeZone: .gmt, year: expiry.year, month: expiry.month, day: expiry.day ?? 1
        )
        // Cannot fail for a valid ExpiryDate.
        guard let date = components.date else { return "" }
        let style = Date.FormatStyle(locale: locale, calendar: gregorian, timeZone: .gmt)
        if expiry.day != nil {
            let text = date.formatted(style.day().month(.wide).year())
            return String(format: bundle.localizedString(forKey: "scan.added.expiry", value: nil, table: nil), text)
        }
        let text = date.formatted(style.month(.wide).year())
        return String(format: bundle.localizedString(forKey: "scan.added.expiryMonth", value: nil, table: nil), text)
    }

    static func announcement(name: String, expiry: ExpiryDate?, bundle: Bundle = .main) -> String {
        String(
            format: bundle.localizedString(forKey: "scan.added.announcement", value: nil, table: nil),
            name, Self.expiry(expiry, bundle: bundle)
        )
    }

    static func format(_ key: String, _ arguments: CVarArg..., bundle: Bundle = .main) -> String {
        String(format: bundle.localizedString(forKey: key, value: nil, table: nil), arguments: arguments)
    }
}
