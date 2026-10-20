import Foundation
import MediCabinetCore

/// The words of one monthly digest: counts only, never a medicine name, a location, a lot, a serial number or a date
/// (N6). Same keys and wording as Android.
enum DigestText {
    static func title(bundle: Bundle = .main) -> String {
        bundle.localizedString(forKey: "reminder.digest.title", value: nil, table: nil)
    }

    /// "2 boxes expired", "3 boxes expire within 60 days" or both parts joined; `nil` when there is nothing to say.
    static func body(
        _ counts: DigestCounts, window: ExpiryWindow, bundle: Bundle = .main, locale: Locale = .current
    ) -> String? {
        let expired = counts.expired > 0 ? expiredPart(counts.expired, bundle: bundle, locale: locale) : nil
        let soon = counts.expiringSoon > 0
            ? soonPart(counts.expiringSoon, window: window, bundle: bundle, locale: locale) : nil
        switch (expired, soon) {
        case (nil, nil):
            return nil
        case (let expired?, nil):
            return expired
        case (nil, let soon?):
            return soon
        case (let expired?, let soon?):
            return String(
                format: bundle.localizedString(forKey: "reminder.digest.body.both", value: nil, table: nil), expired, soon)
        }
    }

    private static func expiredPart(_ count: Int, bundle: Bundle, locale: Locale) -> String {
        String(
            format: bundle.localizedString(forKey: "reminder.digest.body.expired", value: nil, table: nil),
            locale: locale, count)
    }

    private static func soonPart(_ count: Int, window: ExpiryWindow, bundle: Bundle, locale: Locale) -> String {
        String(
            format: bundle.localizedString(forKey: "reminder.digest.body.soon", value: nil, table: nil),
            locale: locale, count, window.days)
    }
}
