import MediCabinetCore
import SwiftUI

/// What was added since the scanner opened, newest first: tap to correct, swipe to delete (with Undo).
struct SessionListView: View {
    let model: ScanViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var editing: Box?

    var body: some View {
        if model.mode == .remove {
            RemovalSessionListView(model: model)
        } else {
            addedList
        }
    }

    private var addedList: some View {
        NavigationStack {
            Group {
                if model.session.count == 0 {
                    ContentUnavailableView("scan.session.empty", systemImage: "tray")
                } else {
                    List(model.session.newestFirst) { box in
                        Button { editing = box } label: { row(box) }
                            .buttonStyle(.plain)
                            .swipeActions {
                                Button(role: .destructive) { Task { await model.delete(box) } } label: {
                                    Label("scan.session.delete", systemImage: "trash")
                                }
                            }
                            .accessibilityHint(Text("scan.session.edit"))
                    }
                    .accessibilityIdentifier("session.list")
                }
            }
            .navigationTitle("scan.session.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("scan.session.close") { dismiss() }.accessibilityIdentifier("session.close")
                }
            }
            .safeAreaInset(edge: .bottom) {
                if case let .deleted(box) = model.notice {
                    HStack {
                        Text("scan.session.deleted")
                        Spacer()
                        Button("scan.undo") { Task { await model.undoDelete(box) } }
                            .buttonStyle(.bordered)
                            .frame(minHeight: 44)
                            .accessibilityIdentifier("session.undoDelete")
                    }
                    .padding()
                    .background(.regularMaterial)
                }
            }
            .sheet(item: $editing) { box in EditBoxSheet(box: box, model: model) }
        }
    }

    private func row(_ box: Box) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: box.product.name).font(.headline)
            Label(ScanText.expiry(box.expiry), systemImage: box.expiry == nil ? "exclamationmark.triangle" : "calendar")
                .font(.subheadline)
            Label(model.locationName(box.locationID), systemImage: "mappin.and.ellipse")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }
}

struct EditBoxSheet: View {
    let box: Box
    let model: ScanViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var expiry: ExpiryChoice
    @State private var locationID: Location.ID

    init(box: Box, model: ScanViewModel) {
        self.box = box
        self.model = model
        _name = State(initialValue: box.product.name)
        _expiry = State(initialValue: ExpiryChoice(box.expiry, editing: true))
        _locationID = State(initialValue: box.locationID)
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("scan.complete.name", text: $name).accessibilityIdentifier("edit.name")
                Picker("manual.location", selection: $locationID) {
                    ForEach(model.locations) { Text(verbatim: $0.name).tag($0.id) }
                }
                Section("scan.complete.expiry") { ExpiryEditor(choice: $expiry) }
            }
            .navigationTitle("scan.session.editTitle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("scan.complete.cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("scan.session.save") {
                        Task {
                            if await model.update(box, name: name, expiry: expiry.expiry, locationID: locationID) { dismiss() }
                        }
                    }
                    .disabled(expiry.resolved == .pending || name.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityIdentifier("edit.save")
                }
            }
        }
        .importBusy()
    }
}

/// The boxes taken out in this Remove session, newest first, each with Undo (R8).
private struct RemovalSessionListView: View {
    let model: ScanViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if model.removalSession.count == 0 {
                    ContentUnavailableView("scan.removal.session.empty", systemImage: "tray")
                } else {
                    List(model.removalSession.newestFirst) { box in
                        HStack {
                            row(box)
                            Button("scan.undo") { Task { await model.undoRemoval(box) } }
                                .buttonStyle(.bordered)
                                .frame(minHeight: 44)
                                .accessibilityIdentifier("session.undoRemoval")
                        }
                    }
                    .accessibilityIdentifier("session.list")
                }
            }
            .navigationTitle("scan.removal.session.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("scan.session.close") { dismiss() }.accessibilityIdentifier("session.close")
                }
            }
        }
    }

    private func row(_ box: Box) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: box.product.name).font(.headline)
            if let reason = box.removal?.reason {
                Text(reason == .usedUp ? "scan.removal.usedUp" : "scan.removal.thrownAway").font(.subheadline)
            }
            Label(ScanText.expiry(box.expiry), systemImage: box.expiry == nil ? "exclamationmark.triangle" : "calendar")
                .font(.footnote)
            Label(model.locationName(box.locationID), systemImage: "mappin.and.ellipse")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }
}
