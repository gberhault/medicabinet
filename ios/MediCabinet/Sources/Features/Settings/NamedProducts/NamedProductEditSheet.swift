import MediCabinetCore
import SwiftUI

/// Edit a named product: its name and category, which apply to every box of the product. **Save** writes;
/// **Cancel** drops the form.
struct NamedProductEditSheet: View {
    let summary: NamedProductSummary
    let model: NamedProductsViewModel

    @Environment(\.dismiss) private var dismiss
    @State private var edit: NamedProductEdit
    @State private var isSaving = false

    init(summary: NamedProductSummary, model: NamedProductsViewModel) {
        self.summary = summary
        self.model = model
        _edit = State(initialValue: NamedProductEdit(from: summary.product))
    }

    /// Why the name cannot be saved; `nil` when it can.
    private var nameProblem: NamedProductEditError? {
        do {
            _ = try edit.validated()
            return nil
        } catch {
            return error == .categoryNotSelectable ? nil : error
        }
    }

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section {
                    TextField("box.edit.name", text: $edit.name)
                        .accessibilityIdentifier("namedProducts.edit.name")
                    LabeledContent("box.code") { Text(verbatim: model.code(of: summary.product)) }
                } footer: {
                    if let problem = nameProblem {
                        Label(problemText(problem), systemImage: "exclamationmark.circle")
                            .accessibilityIdentifier("namedProducts.edit.nameProblem")
                    }
                }
                CategoryPickerSection(
                    category: edit.category, isSetByUser: edit.isCategorySetByUser,
                    identifierPrefix: "namedProducts.edit.category",
                    choose: { try? edit.chooseCategory($0) }, resetToAutomatic: { edit.resetCategory() }
                )
                Section {
                    Text("namedProducts.edit.note")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("namedProducts.edit.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("box.edit.cancel") { dismiss() }
                        .accessibilityIdentifier("namedProducts.edit.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("box.edit.save") { Task { await save() } }
                        .disabled(nameProblem != nil || isSaving)
                        .accessibilityIdentifier("namedProducts.edit.save")
                }
            }
            .alert("box.error", isPresented: Binding(
                get: { model.changeFailed && model.editing != nil }, set: { model.changeFailed = $0 }
            )) {
                Button("locations.changeFailed.ok", role: .cancel) {}
            }
        }
        .interactiveDismissDisabled(isSaving)
        .importBusy()
    }

    private func save() async {
        guard !isSaving else { return }
        isSaving = true
        defer { isSaving = false }
        if await model.save(edit, of: summary) == .saved { dismiss() }
    }

    private func problemText(_ problem: NamedProductEditError) -> LocalizedStringKey {
        switch problem {
        case .emptyName: "box.edit.nameEmpty"
        case .nameTooLong: "box.edit.nameTooLong"
        case .categoryNotSelectable: "box.error"
        }
    }
}
