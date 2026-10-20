import MediCabinetCore
import SwiftUI

/// One box: what it is, where it is, whether it is still good; edit it, mark it opened, or take it out (with Undo).
/// The serial number is never shown.
struct BoxDetailView: View {
    @State private var model: BoxDetailViewModel
    @State private var editor: BoxEditViewModel?
    @Environment(\.openURL) private var openURL
    /// Asks the Cabinet stack to drop this screen (the box left the cabinet).
    let onGone: () -> Void

    init(boxID: Box.ID, environment: AppEnvironment, undo: UndoCenter?, onGone: @escaping () -> Void) {
        _model = State(initialValue: BoxDetailViewModel(boxID: boxID, environment: environment, undo: undo))
        self.onGone = onGone
    }

    var body: some View {
        @Bindable var model = model
        Group {
            if let box = model.box {
                details(of: box)
            } else {
                ProgressView().accessibilityIdentifier("box.loading")
            }
        }
        .navigationTitle("box.detail.title")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("box.edit.action") { editor = model.makeEditor() }
                    .disabled(model.box == nil)
                    .accessibilityIdentifier("box.edit")
            }
        }
        .task { await model.observe() }
        .onChange(of: model.isGone) { _, gone in
            if gone { onGone() }
        }
        .sheet(item: $editor) { BoxEditView(model: $0) }
        .alert("box.error", isPresented: $model.actionFailed) {
            Button("locations.changeFailed.ok", role: .cancel) {}
        }
    }

    private func details(of box: Box) -> some View {
        List {
            Section {
                Text(verbatim: box.product.name)
                    .font(.title3.bold())
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("box.name")
                LabeledContent("box.code") { Text(verbatim: CabinetText.code(gtin: box.product.gtin)) }
                LabeledContent("box.category") {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(LocalizedStringKey(CategoryLabel.key(for: box.category)))
                        if box.categoryOverridden {
                            Label("box.category.manual", systemImage: "person.fill.checkmark")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .accessibilityIdentifier("box.category.manual")
                        }
                    }
                }
                LabeledContent("box.location") { Text(verbatim: model.locationName) }
                LabeledContent("box.expiry") {
                    VStack(alignment: .trailing, spacing: 2) {
                        if box.expiry != nil { Text(verbatim: ScanText.expiry(box.expiry)) }
                        if let status = model.status { ExpiryStatusLabel(status: status) }
                    }
                }
                Toggle("box.opened", isOn: Binding(
                    get: { box.isOpened }, set: { value in Task { await model.setOpened(value) } }
                ))
                .frame(minHeight: 44)
                .accessibilityIdentifier("box.opened")
                if let lot = box.lot, !lot.isEmpty {
                    LabeledContent("box.lot") { Text(verbatim: lot) }
                }
                LabeledContent("box.added") { Text(box.createdAt, format: .dateTime.day().month().year()) }
            } footer: {
                Text("box.category.note")
            }

            leafletSection
            takeOutSection
        }
        .accessibilityIdentifier("box.detail")
    }

    /// The official BDPM page of the medicine (it carries the leaflet), in the system browser. Shown only once the
    /// code has been looked up and the database knows it, below the information so nothing above it moves.
    @ViewBuilder private var leafletSection: some View {
        if let url = model.leaflet {
            Section {
                Button {
                    openURL(url) { accepted in
                        if !accepted { Task { @MainActor in model.openLeafletFailed() } }
                    }
                } label: {
                    Label("box.leaflet.open", systemImage: "arrow.up.right.square")
                }
                .frame(minHeight: 44)
                .accessibilityAddTraits(.isLink)
                .accessibilityRemoveTraits(.isButton)
                .accessibilityHint(Text("box.leaflet.hint"))
                .accessibilityIdentifier("box.leaflet")
            } footer: {
                Text("box.leaflet.note")
            }
        }
    }

    private var takeOutSection: some View {
        Section {
            Button("box.remove.usedUp") { Task { await model.takeOut(.usedUp) } }
                .frame(minHeight: 44)
                .accessibilityIdentifier("box.remove.usedUp")
            Button("box.remove.thrownAway") { Task { await model.takeOut(.thrownAway) } }
                .frame(minHeight: 44)
                .accessibilityIdentifier("box.remove.thrownAway")
            Button("box.remove.mistake", role: .destructive) { Task { await model.deleteAsMistake() } }
                .frame(minHeight: 44)
                .accessibilityIdentifier("box.remove.mistake")
        } header: {
            Text("box.remove.menu")
        } footer: {
            Text("box.remove.mistakeExplanation")
        }
    }
}
