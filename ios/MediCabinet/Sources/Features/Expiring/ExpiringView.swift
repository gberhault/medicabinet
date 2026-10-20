import MediCabinetCore
import SwiftUI

/// The Expiring tab: what is expired, what expires soon and what has no known date, in that order. OK boxes are not
/// listed. The rows open nothing (editing a box's date comes with the box detail).
struct ExpiringView: View {
    let model: ExpiringViewModel

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("tab.expiring")
        }
    }

    @ViewBuilder private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView { Text("expiring.loading") }
                .accessibilityIdentifier("expiring.loading")
        case .failed:
            ContentUnavailableView {
                Label("expiring.error.title", systemImage: "exclamationmark.triangle")
            } description: {
                Text("cabinet.error.message")
            } actions: {
                Button("cabinet.error.retry") { model.retry() }
                    .accessibilityIdentifier("expiring.error.retry")
            }
        case .loaded:
            list
        }
    }

    @ViewBuilder private var list: some View {
        if let overview = model.overview {
            List {
                if overview.isEmpty {
                    Section {
                        ContentUnavailableView {
                            Label("expiring.empty.title", systemImage: "checkmark.seal")
                        } description: {
                            Text(verbatim: ExpiryText.emptyMessage(window: model.window))
                        }
                        .accessibilityIdentifier("expiring.empty")
                    }
                } else {
                    section("expiring.section.expired", id: "expired", entries: overview.expired)
                    section("expiring.section.expiringSoon", id: "expiringSoon", entries: overview.expiringSoon)
                    section("expiring.section.unknown", id: "unknown", entries: overview.unknown)
                }
            }
            .accessibilityIdentifier("expiring.list")
        }
    }

    /// A section with no box is hidden.
    @ViewBuilder private func section(_ title: LocalizedStringKey, id: String, entries: [ExpiringEntry]) -> some View {
        if !entries.isEmpty {
            Section {
                ForEach(entries, id: \.box.id) { entry in
                    ExpiringRow(
                        entry: entry, locationName: model.locationName(entry.box.locationID),
                        onThrowAway: { Task { await model.throwAway(entry) } }
                    )
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            Task { await model.throwAway(entry) }
                        } label: {
                            Label("expiring.thrownAway", systemImage: "trash")
                        }
                        .accessibilityIdentifier("expiring.thrownAway")
                    }
                }
            } header: {
                Text(title)
                    .accessibilityIdentifier("expiring.section.\(id)")
            }
        }
    }
}
