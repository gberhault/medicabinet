import MediCabinetCore
import SwiftUI

/// Adds a box without scanning: no code, a damaged code, or no camera. Works without camera permission.
struct ManualEntryView: View {
    let model: ScanViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var name = ""
    @State private var expiry = ExpiryChoice()
    @State private var locationID: Location.ID?
    @State private var error: ScanViewModel.ManualError?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("manual.code", text: $code)
                        .keyboardType(.numberPad)
                        .accessibilityIdentifier("manual.code")
                    if error == .invalidCode {
                        Label("manual.codeInvalid", systemImage: "xmark.octagon").foregroundStyle(.red)
                    }
                    TextField("manual.name", text: $name)
                        .accessibilityIdentifier("manual.name")
                    if error == .invalidName {
                        Label("manual.nameRequired", systemImage: "xmark.octagon").foregroundStyle(.red)
                    }
                }
                Picker("manual.location", selection: $locationID) {
                    ForEach(model.locations) { Text(verbatim: $0.name).tag(Optional($0.id)) }
                }
                Section("scan.complete.expiry") { ExpiryEditor(choice: $expiry) }
                if error == .failed {
                    Label("scan.error.generic", systemImage: "xmark.octagon").foregroundStyle(.red)
                }
            }
            .navigationTitle("manual.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("scan.complete.cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("manual.add") { Task { await add() } }
                        .disabled(expiry.resolved == .pending || selectedLocation == nil)
                        .accessibilityIdentifier("manual.add")
                }
            }
            .onAppear { locationID = locationID ?? model.sessionLocation?.id }
        }
    }

    private var selectedLocation: Location.ID? { locationID ?? model.sessionLocation?.id }

    private func add() async {
        guard let location = selectedLocation else { return }
        error = await model.addManually(code: code, name: name, expiry: expiry.expiry, locationID: location)
        if error == nil { dismiss() }
    }
}
