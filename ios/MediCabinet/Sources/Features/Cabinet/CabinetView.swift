import MediCabinetCore
import SwiftUI

/// Where a push on the Cabinet stack goes.
enum CabinetRoute: Hashable {
    case product(ProductRowID)
    case box(Box.ID)
}

/// The Cabinet tab: product rows grouped by category, search, location and status filters.
struct CabinetView: View {
    let environment: AppEnvironment
    /// The empty-cabinet button: opens the Scan tab.
    let openScan: () -> Void

    @State private var model: CabinetViewModel
    @State private var path: [CabinetRoute] = []
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.undoCenter) private var undo

    init(environment: AppEnvironment, openScan: @escaping () -> Void) {
        self.environment = environment
        self.openScan = openScan
        _model = State(initialValue: CabinetViewModel(environment: environment))
    }

    var body: some View {
        NavigationStack(path: $path) {
            content
                .navigationTitle("cabinet.title")
                .navigationDestination(for: CabinetRoute.self) { route in
                    switch route {
                    case .product(let id):
                        ProductBoxesView(model: model, rowID: id) { path.removeAll { $0 == .product(id) } }
                    case .box(let id):
                        BoxDetailView(boxID: id, environment: environment, undo: undo) { path.removeAll { $0 == .box(id) } }
                    }
                }
        }
        // Follows the cabinet only while the tab is on screen; a retry restarts it.
        .task(id: model.attempt) { await model.observe() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.refreshToday() }
        }
    }

    @ViewBuilder private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView { Text("cabinet.loading") }
                .accessibilityIdentifier("cabinet.loading")
        case .failed:
            ContentUnavailableView {
                Label("cabinet.error.title", systemImage: "exclamationmark.triangle")
            } description: {
                Text("cabinet.error.message")
            } actions: {
                Button("cabinet.error.retry") { model.retry() }
                    .accessibilityIdentifier("cabinet.error.retry")
            }
        case .loaded:
            if model.isEmptyCabinet {
                ContentUnavailableView {
                    Label("cabinet.empty.title", systemImage: "cross.case")
                } description: {
                    Text("cabinet.empty.message")
                } actions: {
                    Button("cabinet.empty.scan", action: openScan)
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("cabinet.empty.scan")
                }
            } else {
                list
            }
        }
    }

    private var list: some View {
        @Bindable var model = model
        return List {
            Section {
                Text(verbatim: CabinetText.boxCount(model.snapshot.boxCount))
                    .font(.headline)
                    .accessibilityIdentifier("cabinet.count")
                CabinetFilterBar(model: model)
            }
            if model.hasNoResults {
                Section {
                    ContentUnavailableView {
                        Label("cabinet.noResults.title", systemImage: "magnifyingglass")
                    } description: {
                        Text("cabinet.noResults.message")
                    } actions: {
                        Button("cabinet.noResults.clear") { model.clearFilters() }
                            .accessibilityIdentifier("cabinet.noResults.clear")
                    }
                }
            } else {
                ForEach(model.snapshot.sections) { section in
                    Section {
                        ForEach(section.rows) { row in
                            NavigationLink(value: route(for: row)) { ProductRowView(row: row) }
                                .accessibilityIdentifier("cabinet.row")
                        }
                    } header: {
                        Text(LocalizedStringKey(CategoryLabel.key(for: section.category)))
                    }
                }
            }
        }
        .searchable(text: $model.searchText, prompt: Text("cabinet.search.prompt"))
        .accessibilityIdentifier("cabinet.list")
    }

    /// A row with one box goes straight to that box's detail; several boxes open their list.
    private func route(for row: ProductRow) -> CabinetRoute {
        if row.boxes.count == 1, let box = row.boxes.first { return .box(box.id) }
        return .product(row.id)
    }
}

private struct ProductRowView: View {
    let row: ProductRow

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: row.name).font(.headline)
            Text(verbatim: details)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            ExpiryStatusLabel(status: row.status)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
    }

    /// "3 boxes · Expires end of March 2027"; just the count when no box has a known expiry (the status says so).
    private var details: String {
        let count = CabinetText.rowBoxes(row.boxes.count)
        guard let earliest = row.earliestExpiry else { return count }
        return "\(count) · \(ScanText.expiry(earliest))"
    }
}
