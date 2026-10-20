import MediCabinetCore
import SwiftUI

/// The Cabinet's header: the title with the box count, then the search field with the filter menu on its right,
/// then one removable chip per active filter (no space when none is active).
///
/// The search field is a plain text field rather than `.searchable`: the system search bar sits in the navigation
/// bar and cannot share a row with a button, and the filter button must sit right of the field.
struct CabinetHeader: View {
    @Bindable var model: CabinetViewModel
    /// Search and filters only make sense once there is a list; the loading, error and empty states show the title.
    let showsControls: Bool
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            titleRow
            if showsControls {
                // One row; at the largest text sizes the field would not fit beside the button, so they stack.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { searchField; filterMenu }
                    VStack(alignment: .leading, spacing: 4) { searchField; filterMenu }
                }
                if model.activeFilterCount > 0 { chips }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.bar)
    }

    // MARK: Title

    private var titleRow: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 8) { title; count }
            VStack(alignment: .leading, spacing: 2) { title; count }
        }
    }

    private var title: some View {
        Text("cabinet.title")
            .font(typeSize.isAccessibilitySize ? .title.bold() : .largeTitle.bold())
            .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder private var count: some View {
        if showsControls {
            Text(verbatim: CabinetText.boxCount(model.snapshot.boxCount))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("cabinet.count")
        }
    }

    // MARK: Search

    private var searchField: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            TextField("cabinet.search.prompt", text: $model.searchText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .accessibilityIdentifier("cabinet.search")
            if !model.searchText.isEmpty {
                Button { model.searchText = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .frame(width: 44, height: 44)
                .accessibilityLabel(Text("cabinet.search.clear"))
                .accessibilityIdentifier("cabinet.search.clear")
            }
        }
        .padding(.leading, 10)
        .frame(minHeight: 44)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.tertiarySystemFill)))
    }

    // MARK: Filter menu

    private var filterMenu: some View {
        Menu {
            Section("cabinet.filter.location") {
                Picker("cabinet.filter.location", selection: locationSelection) {
                    Text("cabinet.filter.allLocations").tag(Location.ID?.none)
                    ForEach(model.locations) { location in
                        Text(verbatim: location.name).tag(Optional(location.id))
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
            Section("cabinet.filter.status") {
                Picker("cabinet.filter.status", selection: statusSelection) {
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
                .pickerStyle(.inline)
                .labelsHidden()
            }
            if model.activeFilterCount > 0 {
                Button(role: .destructive) {
                    model.clearLocation()
                    model.clearStatus()
                } label: {
                    Text("cabinet.filter.clear")
                }
                .accessibilityIdentifier("cabinet.filter.clear")
            }
        } label: {
            Image(systemName: model.activeFilterCount > 0
                ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                .font(.title2)
                // The icon stays tappable-sized rather than growing with the text (the label carries the meaning).
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(Text("cabinet.filter.button"))
        .accessibilityValue(Text(verbatim: activeCountText))
        .accessibilityIdentifier("cabinet.filter.button")
    }

    /// Spoken as the button's value; empty when no filter is on.
    private var activeCountText: String {
        model.activeFilterCount == 0 ? "" : CabinetText.activeFilters(model.activeFilterCount)
    }

    private var locationSelection: Binding<Location.ID?> {
        Binding(get: { model.filter.locationID }, set: { model.selectLocation($0) })
    }

    private var statusSelection: Binding<ExpiryStatus?> {
        Binding(get: { model.filter.status }, set: { model.selectStatus($0) })
    }

    /// Expired, Expiring soon, Expiry unknown, OK.
    static let statusOrder: [ExpiryStatus] = [.expired, .expiringSoon, .unknown, .ok]

    // MARK: Chips

    private var chips: some View {
        FlowLayout(spacing: 8, lineSpacing: 0) {
            if let id = model.filter.locationID {
                chip(icon: "mappin.and.ellipse", text: model.locationName(id), remove: { model.clearLocation() })
            }
            if let status = model.filter.status {
                chip(
                    icon: ExpiryStatusStyle.symbol(status),
                    text: String(localized: String.LocalizationValue(ExpiryStatusStyle.key(status))),
                    remove: { model.clearStatus() })
            }
        }
    }

    private func chip(icon: String, text: String, remove: @escaping () -> Void) -> some View {
        Button(action: remove) {
            HStack(spacing: 4) {
                Image(systemName: icon).accessibilityHidden(true)
                Text(verbatim: text)
                Image(systemName: "xmark").font(.caption).accessibilityHidden(true)
            }
            .font(.subheadline)
            .foregroundStyle(.primary)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Capsule().fill(Color(.tertiarySystemFill)))
        }
        .buttonStyle(.plain)
        .frame(minHeight: 44)
        .accessibilityLabel(Text(verbatim: CabinetText.removeFilter(text)))
        .accessibilityIdentifier("cabinet.filter.chip")
    }
}
