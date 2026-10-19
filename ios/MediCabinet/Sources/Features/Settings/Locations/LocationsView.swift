import MediCabinetCore
import SwiftUI

/// Settings → Locations: where the household keeps its medicines (bathroom, kitchen, car, travel kit…).
struct LocationsView: View {
    /// What the name sheet is for.
    enum Naming: Identifiable {
        case add
        case rename(Location)

        var id: String {
            switch self {
            case .add: "add"
            case .rename(let location): "rename-\(location.id)"
            }
        }
    }

    @State private var model: LocationsViewModel
    @State private var naming: Naming?

    init(repository: any LocationRepository, undo: UndoCenter?) {
        _model = State(initialValue: LocationsViewModel(repository: repository, undo: undo))
    }

    var body: some View {
        @Bindable var model = model
        Group {
            switch model.phase {
            case .loading:
                ProgressView { Text("locations.loading") }
                    .accessibilityIdentifier("locations.loading")
            case .failed:
                ContentUnavailableView {
                    Label("locations.error.title", systemImage: "exclamationmark.triangle")
                } description: {
                    Text("locations.error.message")
                } actions: {
                    Button("locations.error.retry") { Task { await model.observe() } }
                }
            case .loaded:
                list
            }
        }
        .navigationTitle("locations.title")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { naming = .add } label: { Label("locations.add", systemImage: "plus") }
                    .accessibilityIdentifier("locations.add")
            }
            ToolbarItem(placement: .topBarLeading) { EditButton() }
        }
        .task { await model.observe() }
        .sheet(item: $naming) { LocationNameSheet(naming: $0, model: model) }
        .sheet(item: $model.deletion) { DeleteLocationSheet(request: $0, model: model) }
        .alert("locations.changeFailed.title", isPresented: $model.changeFailed) {
            Button("locations.changeFailed.ok", role: .cancel) {}
        } message: {
            Text("locations.changeFailed.message")
        }
    }

    private var list: some View {
        List {
            Section {
                ForEach(model.locations) { location in
                    row(for: location)
                }
                .onMove { source, destination in
                    Task { await model.move(fromOffsets: source, toOffset: destination) }
                }
            } footer: {
                Text("locations.defaultNotDeletable")
            }
        }
        .accessibilityIdentifier("locations.list")
    }

    private func row(for location: Location) -> some View {
        HStack {
            Text(verbatim: location.name)
            Spacer()
            if location.isDefault {
                Label("locations.default", systemImage: "star.fill")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("locations.default")
            }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: Text("locations.move.up")) { Task { await model.moveUp(location) } }
        .accessibilityAction(named: Text("locations.move.down")) { Task { await model.moveDown(location) } }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !location.isDefault {
                Button(role: .destructive) {
                    Task { await model.requestDeletion(of: location) }
                } label: {
                    Label("locations.delete", systemImage: "trash")
                }
            }
            Button { naming = .rename(location) } label: { Label("locations.rename", systemImage: "pencil") }
                .tint(.blue)
        }
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            if !location.isDefault {
                Button { Task { await model.makeDefault(location) } } label: {
                    Label("locations.makeDefault", systemImage: "star")
                }
                .tint(.orange)
            }
        }
        .contextMenu {
            Button { naming = .rename(location) } label: { Label("locations.rename", systemImage: "pencil") }
            if !location.isDefault {
                Button { Task { await model.makeDefault(location) } } label: {
                    Label("locations.makeDefault", systemImage: "star")
                }
                Button(role: .destructive) {
                    Task { await model.requestDeletion(of: location) }
                } label: {
                    Label("locations.delete", systemImage: "trash")
                }
            }
        }
    }
}
