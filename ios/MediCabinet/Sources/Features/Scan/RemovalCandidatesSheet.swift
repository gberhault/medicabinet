import MediCabinetCore
import SwiftUI

/// Several boxes match the scan: the user picks the one taken out (R7). The first is pre-selected; Cancel removes
/// nothing. The camera ignores recognitions while this is open.
struct RemovalCandidatesSheet: View {
    let candidates: ScanViewModel.Candidates
    let model: ScanViewModel
    @State private var selection: Box.ID?

    init(candidates: ScanViewModel.Candidates, model: ScanViewModel) {
        self.candidates = candidates
        self.model = model
        _selection = State(initialValue: candidates.boxes.first?.id)
    }

    var body: some View {
        NavigationStack {
            List(candidates.boxes) { box in
                Button { selection = box.id } label: { row(box) }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selection == box.id ? .isSelected : [])
                    .accessibilityIdentifier("candidate.row")
            }
            .navigationTitle("scan.removal.choose.title")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .top) {
                Text("scan.removal.choose.message")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("scan.removal.choose.cancel") { model.cancelCandidates() }
                        .accessibilityIdentifier("candidate.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("scan.removal.choose.remove") {
                        if let selection { Task { await model.removeCandidate(selection) } }
                    }
                    .disabled(selection == nil)
                    .accessibilityIdentifier("candidate.remove")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ box: Box) -> some View {
        HStack(spacing: 12) {
            Image(systemName: selection == box.id ? "largecircle.fill.circle" : "circle")
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: box.product.name).font(.headline)
                Label(ScanText.expiry(box.expiry), systemImage: box.expiry == nil ? "exclamationmark.triangle" : "calendar")
                    .font(.subheadline)
                Label(model.locationName(box.locationID), systemImage: "mappin.and.ellipse")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if box.isOpened {
                    Label("scan.removal.choose.opened", systemImage: "seal").font(.footnote)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
