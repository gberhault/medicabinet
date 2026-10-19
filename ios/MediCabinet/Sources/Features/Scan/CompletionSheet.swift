import MediCabinetCore
import SwiftUI

/// Asked right after a scan, while the box is still in hand: only what the code could not tell.
struct CompletionSheet: View {
    let completion: ScanViewModel.Completion
    let onConfirm: (String?, ExpiryDate?) -> Void
    let onCancel: () -> Void

    @State private var name = ""
    @State private var expiry = ExpiryChoice()

    private var asksName: Bool { completion.missing.contains(.name) }
    private var asksExpiry: Bool { completion.missing.contains(.expiry) }

    private var canAdd: Bool {
        let nameOK = !asksName || !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return nameOK && (!asksExpiry || expiry.resolved != .pending)
    }

    var body: some View {
        NavigationStack {
            Form {
                if let gtin = completion.draft.gtin {
                    Section { Text(verbatim: ScanText.format("scan.complete.code", gtin)) }
                }
                if asksName {
                    Section("scan.complete.name") {
                        TextField("scan.complete.name", text: $name)
                            .accessibilityIdentifier("completion.name")
                    }
                }
                if asksExpiry {
                    Section("scan.complete.expiry") {
                        ExpiryEditor(choice: $expiry, showsUnreadableWarning: completion.expiryUnreadable)
                    }
                }
            }
            .navigationTitle("scan.complete.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("scan.complete.cancel", action: onCancel)
                        .accessibilityIdentifier("completion.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("scan.complete.add") { onConfirm(asksName ? name : nil, expiry.expiry) }
                        .disabled(!canAdd)
                        .accessibilityIdentifier("completion.add")
                }
            }
        }
        .interactiveDismissDisabled()
    }
}
