import MediCabinetCore
import SwiftUI

/// The confirmation (or refusal) of the last scan. Text and icon carry the meaning, not colour alone.
struct NoticeCard: View {
    let notice: ScanViewModel.Notice
    let model: ScanViewModel

    var body: some View {
        HStack(spacing: 12) {
            content
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("scan.notice")
        .task(id: notice) {
            try? await Task.sleep(for: .seconds(6))
            if !Task.isCancelled { model.dismissNotice() }
        }
    }

    @ViewBuilder private var content: some View {
        switch notice {
        case let .added(box):
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).accessibilityHidden(true)
            summary(box)
            action("scan.undo", id: "scan.notice.undo") { await model.delete(box) }
        case let .alreadyInCabinet(_, locationName):
            Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.orange).accessibilityHidden(true)
            Text(verbatim: ScanText.format("scan.duplicate.inCabinet", locationName))
        case let .removedEarlier(box):
            Image(systemName: "arrow.uturn.backward.circle.fill").foregroundStyle(.orange).accessibilityHidden(true)
            VStack(alignment: .leading) {
                Text(verbatim: box.product.name).font(.headline)
                Text("scan.duplicate.removedEarlier").font(.footnote)
            }
            action("scan.duplicate.putBack", id: "scan.notice.putBack") { await model.putBack(box) }
        case let .putBack(box):
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).accessibilityHidden(true)
            VStack(alignment: .leading) {
                Text(verbatim: box.product.name).font(.headline)
                Text("scan.putBack.done").font(.footnote)
            }
        case let .deleted(box):
            Image(systemName: "trash.circle.fill").accessibilityHidden(true)
            Text("scan.session.deleted")
            action("scan.undo", id: "scan.notice.undoDelete") { await model.undoDelete(box) }
        case .unrecognized:
            Image(systemName: "questionmark.circle.fill").foregroundStyle(.orange).accessibilityHidden(true)
            Text("scan.unrecognized")
        case .failed:
            Image(systemName: "xmark.octagon.fill").foregroundStyle(.red).accessibilityHidden(true)
            Text("scan.error.generic")
        }
    }

    private func summary(_ box: Box) -> some View {
        VStack(alignment: .leading) {
            Text(verbatim: box.product.name).font(.headline)
            Label(ScanText.expiry(box.expiry), systemImage: box.expiry == nil ? "exclamationmark.triangle" : "calendar")
                .font(.footnote)
        }
    }

    private func action(_ title: LocalizedStringKey, id: String, perform: @escaping () async -> Void) -> some View {
        Button(title) { Task { await perform() } }
            .buttonStyle(.bordered)
            .frame(minHeight: 44)
            .accessibilityIdentifier(id)
    }
}

/// "Another box of X?": does not stop the camera; the next scan dismisses it.
struct AnotherCard: View {
    let another: ScanViewModel.Another
    let model: ScanViewModel

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "plus.circle.fill").accessibilityHidden(true)
            Text(verbatim: ScanText.format("scan.another.question", another.draft.name ?? another.draft.gtin ?? ""))
            Spacer()
            Button("scan.another.add") { Task { await model.confirmAnother() } }
                .buttonStyle(.borderedProminent)
                .frame(minHeight: 44)
                .accessibilityIdentifier("scan.another.add")
        }
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("scan.another")
    }
}
