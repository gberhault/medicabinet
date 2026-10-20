import MediCabinetCore
import SwiftUI

/// An expiry status as text and an icon, never colour alone. The text stays in the primary colour (WCAG AA in light
/// and dark); only the icon is tinted. An unknown expiry is shown as such, never as OK.
struct ExpiryStatusLabel: View {
    let status: ExpiryStatus

    var body: some View {
        Label {
            Text(ExpiryStatusStyle.title(status))
        } icon: {
            Image(systemName: ExpiryStatusStyle.symbol(status))
                .foregroundStyle(ExpiryStatusStyle.tint(status))
        }
        .font(.subheadline)
        .accessibilityIdentifier("expiry.status.\(status.rawValue)")
    }
}

enum ExpiryStatusStyle {
    static func title(_ status: ExpiryStatus) -> LocalizedStringKey {
        switch status {
        case .expired: "expiry.status.expired"
        case .expiringSoon: "expiry.status.expiringSoon"
        case .ok: "expiry.status.ok"
        case .unknown: "expiry.status.unknown"
        }
    }

    /// String catalog key, for places that need a `String` (menus, filter summaries).
    static func key(_ status: ExpiryStatus) -> String {
        "expiry.status.\(status.rawValue)"
    }

    static func symbol(_ status: ExpiryStatus) -> String {
        switch status {
        case .expired: "xmark.octagon.fill"
        case .expiringSoon: "exclamationmark.triangle.fill"
        case .ok: "checkmark.circle.fill"
        case .unknown: "questionmark.circle.fill"
        }
    }

    static func tint(_ status: ExpiryStatus) -> Color {
        switch status {
        case .expired: .red
        case .expiringSoon: .orange
        case .ok: .green
        case .unknown: .secondary
        }
    }
}
