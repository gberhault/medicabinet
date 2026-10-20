import MediCabinetCore
import SwiftUI

/// The edit form: name, expiry (with **Date not printed**), location, category. **Save** writes; **Cancel** drops it.
struct BoxEditView: View {
    @State var model: BoxEditViewModel
    @Environment(\.dismiss) private var dismiss

    init(model: BoxEditViewModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section {
                    TextField("box.edit.name", text: $model.name)
                        .accessibilityIdentifier("box.edit.name")
                } footer: {
                    if let problem = model.nameProblem {
                        Label(problemText(problem), systemImage: "exclamationmark.circle")
                            .accessibilityIdentifier("box.edit.nameProblem")
                    }
                }
                Section("box.expiry") {
                    ExpiryEditor(choice: $model.expiry)
                }
                Section("box.location") {
                    Picker("box.location", selection: $model.locationID) {
                        ForEach(model.locations) { location in
                            Text(verbatim: location.name).tag(location.id)
                        }
                    }
                    .accessibilityIdentifier("box.edit.location")
                }
                CategoryPickerSection(
                    category: model.category, isSetByUser: model.isCategorySetByUser,
                    identifierPrefix: "box.edit.category", choose: { model.choose($0) },
                    resetToAutomatic: { Task { await model.resetToAutomatic() } }
                )
            }
            .navigationTitle("box.edit.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("box.edit.cancel") { dismiss() }
                        .accessibilityIdentifier("box.edit.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("box.edit.save") {
                        Task { if await model.save() { dismiss() } }
                    }
                    .disabled(!model.canSave)
                    .accessibilityIdentifier("box.edit.save")
                }
            }
            .alert("box.error", isPresented: $model.failed) {
                Button("locations.changeFailed.ok", role: .cancel) {}
            }
        }
        .interactiveDismissDisabled(model.isSaving)
        .importBusy()
    }

    private func problemText(_ problem: BoxEditError) -> LocalizedStringKey {
        switch problem {
        case .emptyName: "box.edit.nameEmpty"
        case .nameTooLong: "box.edit.nameTooLong"
        case .categoryNotSelectable: "box.error"
        }
    }
}
