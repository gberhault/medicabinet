import MediCabinetCore
import SwiftUI

/// The location and expiry-status menus, then the active filters as text with a one-tap **Clear**.
struct CabinetFilterBar: View {
    let model: CabinetViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) { locationMenu; statusMenu }
                VStack(alignment: .leading, spacing: 4) { locationMenu; statusMenu }
            }
            if model.filter.isActive {
                activeFilters
            }
        }
    }

    private var locationMenu: some View {
        Menu {
            Picker("cabinet.filter.location", selection: Binding(
                get: { model.filter.locationID }, set: { model.selectLocation($0) }
            )) {
                Text("cabinet.filter.allLocations").tag(Location.ID?.none)
                ForEach(model.locations) { location in
                    Text(verbatim: location.name).tag(Optional(location.id))
                }
            }
        } label: {
            Label {
                if let id = model.filter.locationID {
                    Text(verbatim: model.locationName(id))
                } else {
                    Text("cabinet.filter.allLocations")
                }
            } icon: {
                Image(systemName: "mappin.and.ellipse")
            }
            .frame(minHeight: 44)
        }
        .accessibilityLabel(Text("cabinet.filter.location"))
        .accessibilityIdentifier("cabinet.filter.location")
    }

    private var statusMenu: some View {
        Menu {
            Picker("cabinet.filter.status", selection: Binding(
                get: { model.filter.status }, set: { model.selectStatus($0) }
            )) {
                Text("cabinet.filter.all").tag(ExpiryStatus?.none)
                ForEach(Self.statusOrder, id: \.self) { status in
                    Label {
                        Text(ExpiryStatusStyle.title(status))
                    } icon: {
                        Image(systemName: ExpiryStatusStyle.symbol(status))
                    }
                    .tag(Optional(status))
                }
            }
        } label: {
            Label {
                if let status = model.filter.status {
                    Text(ExpiryStatusStyle.title(status))
                } else {
                    Text("cabinet.filter.status")
                }
            } icon: {
                Image(systemName: "calendar")
            }
            .frame(minHeight: 44)
        }
        .accessibilityLabel(Text("cabinet.filter.status"))
        .accessibilityIdentifier("cabinet.filter.status")
    }

    /// All, then Expired, Expiring soon, Expiry unknown, OK.
    static let statusOrder: [ExpiryStatus] = [.expired, .expiringSoon, .unknown, .ok]

    private var activeFilters: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: String(
                format: String(localized: "cabinet.filter.active"), model.activeFilterParts.joined(separator: ", ")
            ))
            .font(.footnote)
            .foregroundStyle(.secondary)
            .accessibilityIdentifier("cabinet.filter.summary")
            Button("cabinet.filter.clear") { model.clearFilters() }
                .frame(minHeight: 44)
                .accessibilityIdentifier("cabinet.filter.clear")
        }
    }
}
