import MediCabinetCore
import SwiftUI

/// One product of the Cabinet as a pill: its displayed name and its box count. No date and no "OK": only the alert
/// statuses (expired, expiring soon, unknown expiry) are marked, with an icon and a tint — never colour alone. The
/// pill draws about 32 pt high and takes taps over 44 pt.
struct ProductPill: View {
    let row: ProductRow

    var body: some View {
        let style = PillStyle.style(for: row.status)
        HStack(spacing: 4) {
            if let symbol = style.symbol {
                Image(systemName: symbol).font(.footnote).accessibilityHidden(true)
            }
            Text(verbatim: row.displayName)
                .lineLimit(2)
                .truncationMode(.tail)
                .layoutPriority(1)
            Text(verbatim: "×\(row.boxes.count)")
                .fontWeight(.semibold)
                .fixedSize()
        }
        .font(.subheadline)
        .foregroundStyle(style.foreground)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Capsule().fill(style.background))
        .overlay(Capsule().strokeBorder(style.outline, lineWidth: 1))
        // The tap target: 32 pt of pill plus 6 pt above and below.
        .padding(.vertical, 6)
        .frame(minWidth: 44, minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            Text(verbatim: CabinetText.pillLabel(name: row.name, boxes: row.boxes.count, status: row.status)))
    }
}
