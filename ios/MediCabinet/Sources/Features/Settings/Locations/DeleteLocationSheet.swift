import MediCabinetCore
import SwiftUI

/// Deleting a location that still holds boxes: say how many and where they go (the default location is
/// pre-selected). Nothing changes until **Move and delete**.
struct DeleteLocationSheet: View {
    let request: LocationsViewModel.Deletion
    let model: LocationsViewModel

    @Environment(\.dismiss) private var dismiss
    @State private var destination: Location.ID?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(verbatim: model.deletionMessage(for: request))
                        .accessibilityIdentifier("locations.delete.message")
                }
                Section {
                    Picker("locations.delete.destination", selection: $destination) {
                        ForEach(request.destinations) { location in
                            Text(verbatim: location.name).tag(Optional(location.id))
                        }
                    }
                    .pickerStyle(.inline)
                    .accessibilityIdentifier("locations.delete.destination")
                }
            }
            .navigationTitle("locations.delete.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("locations.name.cancel") {
                        model.deletion = nil
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("locations.delete.confirm", role: .destructive) {
                        guard let destination else { return }
                        Task { await model.confirm(request, movingBoxesTo: destination) }
                    }
                    .disabled(destination == nil)
                    .accessibilityIdentifier("locations.delete.confirm")
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear { destination = model.suggestedDestination(for: request) }
    }
}
