import MediCabinetCore
import SwiftUI

/// The boxes of a product row, earliest expiry first (unknown last); the current filters apply. It follows the list:
/// when the last box leaves, it asks to go back to the Cabinet.
struct ProductBoxesView: View {
    let model: CabinetViewModel
    let rowID: ProductRowID
    let onEmpty: () -> Void

    var body: some View {
        let row = model.row(rowID)
        List {
            if let row {
                ForEach(row.boxes) { box in
                    NavigationLink(value: CabinetRoute.box(box.id)) { BoxRow(box: box, model: model) }
                        .accessibilityIdentifier("product.box")
                }
            }
        }
        .navigationTitle(Text(verbatim: row?.name ?? ""))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("product.boxes")
        .onChange(of: row == nil) { _, gone in
            if gone { onEmpty() }
        }
    }
}

private struct BoxRow: View {
    let box: Box
    let model: CabinetViewModel

    var body: some View {
        let status = model.status(of: box)
        VStack(alignment: .leading, spacing: 4) {
            if box.expiry != nil {
                Text(verbatim: ScanText.expiry(box.expiry)).font(.headline)
            }
            ExpiryStatusLabel(status: status)
            Label(model.locationName(box.locationID), systemImage: "mappin.and.ellipse")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if box.isOpened {
                Label("box.opened", systemImage: "lock.open")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .contentShape(Rectangle())
    }
}
