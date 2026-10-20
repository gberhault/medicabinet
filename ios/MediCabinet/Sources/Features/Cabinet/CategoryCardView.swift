import MediCabinetCore
import SwiftUI

extension CategorySection {
    /// Boxes in the card: the sum over its rows (after the filters).
    var boxCount: Int { rows.reduce(0) { $0 + $1.boxes.count } }
}

/// A category of the Cabinet: its icon, label and box total, then its products as pills that wrap across the width.
struct CategoryCardView: View {
    let section: CategorySection
    /// Where a tap on a pill goes.
    let route: (ProductRow) -> CabinetRoute

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            FlowLayout(spacing: 6, lineSpacing: 0) {
                ForEach(section.rows) { row in
                    NavigationLink(value: route(row)) { ProductPill(row: row) }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("cabinet.pill")
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 12)
        .padding(.bottom, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(.secondarySystemGroupedBackground)))
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: CategoryIcon.symbol(for: section.category))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(LocalizedStringKey(CategoryLabel.key(for: section.category)))
                .font(.headline)
            Spacer(minLength: 8)
            Text(verbatim: CabinetText.rowBoxes(section.boxCount))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.bottom, 6)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .accessibilityIdentifier("cabinet.card.header")
    }
}
