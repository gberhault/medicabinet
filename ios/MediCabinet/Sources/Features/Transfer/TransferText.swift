import Foundation

/// User-facing text of export, import and backups: always from the String Catalog (plurals included), dates in the
/// user's locale.
enum TransferText {
    static func string(_ key: String, bundle: Bundle = .main) -> String {
        bundle.localizedString(forKey: key, value: nil, table: nil)
    }

    static func format(_ key: String, _ arguments: CVarArg..., bundle: Bundle = .main) -> String {
        String(format: string(key, bundle: bundle), arguments: arguments)
    }

    /// Date and time, medium style, in the user's locale and time zone (strings.md).
    static func dateTime(_ date: Date, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        var style = Date.FormatStyle(date: .abbreviated, time: .shortened, locale: locale)
        style.timeZone = timeZone
        return date.formatted(style)
    }
}
