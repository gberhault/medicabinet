import MediCabinetCore
import SwiftUI

/// One box that needs a look: name, where it is, its date and its status as text and an icon (never colour alone).
/// The row opens nothing; **Thrown away** is a swipe action and an accessibility action.
struct ExpiringRow: View {
    let entry: ExpiringEntry
    let locationName: String
    /// Takes the box out as thrown away.
    let onThrowAway: () -> Void

    private var status: ExpiryStatus { entry.assessment.status }
    private var expiryLine: String? { ExpiryText.expiryLine(entry.box.expiry) }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: entry.box.product.name)
                .font(.headline)
            if !locationName.isEmpty {
                Text(verbatim: locationName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if let expiryLine {
                Text(verbatim: expiryLine)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ExpiryStatusLabel(status: status, expiresToday: entry.assessment.expiresToday)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: spokenLabel))
        .accessibilityAction(named: Text("expiring.thrownAway"), onThrowAway)
        .accessibilityIdentifier("expiring.row")
    }

    /// The status as words, "Expires today" included.
    private var spokenStatus: String {
        let key = entry.assessment.expiresToday ? ExpiryStatusStyle.expiresTodayKey : ExpiryStatusStyle.key(status)
        return String(localized: String.LocalizationValue(key))
    }

    private var spokenLabel: String {
        ExpiryText.rowAccessibility(
            name: entry.box.product.name, status: spokenStatus, expiryLine: expiryLine, location: locationName)
    }
}
