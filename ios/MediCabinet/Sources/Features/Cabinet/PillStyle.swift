import MediCabinetCore
import SwiftUI

/// How a product pill of the Cabinet looks for its row status. Only the alert statuses (expired, expiring soon,
/// unknown) are marked; `ok` is a neutral pill with no icon and no text. The status is never carried by colour
/// alone: each alert has its own icon, and the unknown pill is outlined. The colour pairs are asset colours with
/// light and dark variants, checked at 4.5:1 by `PillStyleTests`; the same values are on Android (`PillColors`).
struct PillStyle: Equatable {
    let background: Color
    let foreground: Color
    /// Drawn around the pill; clear for every status but unknown.
    let outline: Color
    /// `nil` for an `ok` pill.
    let symbol: String?

    /// Names of the asset colours of an alert status, or `nil` when the status has none (unknown and ok use system
    /// colours).
    static func assetNames(for status: ExpiryStatus) -> (background: String, foreground: String)? {
        switch status {
        case .expired: ("PillExpiredBackground", "PillExpiredForeground")
        case .expiringSoon: ("PillSoonBackground", "PillSoonForeground")
        case .unknown, .ok: nil
        }
    }

    static func style(for status: ExpiryStatus) -> PillStyle {
        if let names = assetNames(for: status) {
            return PillStyle(
                background: Color(names.background), foreground: Color(names.foreground), outline: .clear,
                symbol: ExpiryStatusStyle.symbol(status))
        }
        switch status {
        case .unknown:
            return PillStyle(
                background: Color(.tertiarySystemFill), foreground: Color(.label), outline: Color(.secondaryLabel),
                symbol: ExpiryStatusStyle.symbol(status))
        default:
            return PillStyle(
                background: Color(.tertiarySystemFill), foreground: Color(.label), outline: .clear, symbol: nil)
        }
    }
}
