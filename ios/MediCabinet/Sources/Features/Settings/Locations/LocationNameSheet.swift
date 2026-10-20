import MediCabinetCore
import SwiftUI

/// Create or rename a location. The message under the field says why a name is refused.
struct LocationNameSheet: View {
    let naming: LocationsView.Naming
    let model: LocationsViewModel

    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var problem: LocationsViewModel.NameProblem?
    @State private var isSaving = false
    @FocusState private var focused: Bool

    private var renaming: Location? {
        if case .rename(let location) = naming { return location }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("locations.name.field", text: $text)
                        .focused($focused)
                        .submitLabel(.done)
                        .onSubmit { Task { await save() } }
                        .accessibilityIdentifier("locations.name.field")
                } footer: {
                    if let problem {
                        Label(message(for: problem), systemImage: "exclamationmark.circle")
                            .accessibilityIdentifier("locations.name.problem")
                    }
                }
            }
            .navigationTitle(renaming == nil ? "locations.name.addTitle" : "locations.name.renameTitle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("locations.name.cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("locations.name.save") { Task { await save() } }
                        .disabled(isSaving || TextNormalizer.trimmed(text).isEmpty)
                        .accessibilityIdentifier("locations.name.save")
                }
            }
        }
        .presentationDetents([.medium])
        .onAppear {
            text = renaming?.name ?? ""
            focused = true
        }
        .onChange(of: text) { problem = nil }
        .importBusy()
    }

    private func save() async {
        guard !isSaving else { return }
        isSaving = true
        defer { isSaving = false }
        let refused: LocationsViewModel.NameProblem?
        if let renaming {
            refused = await model.rename(renaming, to: text)
        } else {
            refused = await model.add(text)
        }
        if let refused {
            problem = refused
        } else {
            dismiss()
        }
    }

    private func message(for problem: LocationsViewModel.NameProblem) -> LocalizedStringKey {
        switch problem {
        case .empty: "locations.name.empty"
        case .tooLong: "locations.name.tooLong"
        case .duplicate: "locations.name.duplicate"
        }
    }
}
