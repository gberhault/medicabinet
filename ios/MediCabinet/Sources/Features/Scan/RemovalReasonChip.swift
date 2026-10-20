import MediCabinetCore
import SwiftUI

/// The session's removal reason, changeable in one tap (R3): the choice is remembered for next time.
struct RemovalReasonChip: View {
    let model: ScanViewModel

    var body: some View {
        Menu {
            Picker(selection: Binding(get: { model.removalReason }, set: { model.setRemovalReason($0) })) {
                ForEach(RemovalReason.allCases, id: \.self) { reason in
                    Text(Self.label(reason)).tag(reason)
                }
            } label: {
                Text("scan.removal.reason")
            }
        } label: {
            Label(Self.label(model.removalReason), systemImage: "arrow.triangle.2.circlepath")
                .lineLimit(1)
                .frame(minHeight: 44)
        }
        .accessibilityLabel(Text("scan.removal.reason"))
        .accessibilityValue(Text(Self.label(model.removalReason)))
        .accessibilityIdentifier("scan.removal.reason")
    }

    static func label(_ reason: RemovalReason) -> LocalizedStringKey {
        reason == .usedUp ? "scan.removal.usedUp" : "scan.removal.thrownAway"
    }
}
