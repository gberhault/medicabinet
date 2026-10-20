import MediCabinetCore
import SwiftUI

/// Settings → Products you named: the names the household gave to codes the medicines database does not know. Edit
/// renames the product and all its boxes; Forget makes the next scan of the code ask for a name again.
struct NamedProductsView: View {
    @State private var model: NamedProductsViewModel

    init(repository: any NamedProductRepository, undo: UndoCenter?) {
        _model = State(initialValue: NamedProductsViewModel(repository: repository, undo: undo))
    }

    var body: some View {
        @Bindable var model = model
        Group {
            switch model.phase {
            case .loading:
                ProgressView { Text("locations.loading") }
                    .accessibilityIdentifier("namedProducts.loading")
            case .failed:
                ContentUnavailableView {
                    Label("namedProducts.error.title", systemImage: "exclamationmark.triangle")
                } description: {
                    Text("namedProducts.error.message")
                } actions: {
                    Button("locations.error.retry") { Task { await model.observe() } }
                }
                .accessibilityIdentifier("namedProducts.error")
            case .loaded(let summaries) where summaries.isEmpty:
                ContentUnavailableView {
                    Label("namedProducts.empty.title", systemImage: "tag")
                } description: {
                    Text("namedProducts.empty.message")
                }
                .accessibilityIdentifier("namedProducts.empty")
            case .loaded(let summaries):
                list(summaries)
            }
        }
        .navigationTitle("namedProducts.title")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
        .sheet(item: $model.editing) { NamedProductEditSheet(summary: $0, model: model) }
        .confirmationDialog(
            model.forgetRequest.map { Text(verbatim: model.forgetTitle(for: $0)) } ?? Text(verbatim: ""),
            isPresented: Binding(get: { model.forgetRequest != nil }, set: { if !$0 { model.cancelForget() } }),
            titleVisibility: .visible, presenting: model.forgetRequest
        ) { request in
            Button("namedProducts.forget", role: .destructive) { Task { await model.confirmForget(request) } }
                .accessibilityIdentifier("namedProducts.forget.confirm")
            Button("box.edit.cancel", role: .cancel) {}
        } message: { request in
            Text(verbatim: model.forgetMessage(for: request))
        }
        // The edit sheet shows its own alert while it is open.
        .alert("box.error", isPresented: Binding(
            get: { model.changeFailed && model.editing == nil }, set: { model.changeFailed = $0 }
        )) {
            Button("locations.changeFailed.ok", role: .cancel) {}
        }
    }

    private func list(_ summaries: [NamedProductSummary]) -> some View {
        List(summaries) { summary in
            row(for: summary)
        }
        .accessibilityIdentifier("namedProducts.list")
    }

    private func row(for summary: NamedProductSummary) -> some View {
        let product = summary.product
        return Button { model.edit(summary) } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: product.name)
                    .font(.headline)
                    .foregroundStyle(.primary)
                Text(verbatim: "\(model.code(of: product)) · \(model.category(of: product))")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text(verbatim: model.boxCount(of: summary))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("namedProducts.row")
        .accessibilityAction(named: Text("box.edit.action")) { model.edit(summary) }
        .accessibilityAction(named: Text("namedProducts.forget")) { model.requestForget(summary) }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) { model.requestForget(summary) } label: {
                Label("namedProducts.forget", systemImage: "eraser")
            }
            Button { model.edit(summary) } label: { Label("box.edit.action", systemImage: "pencil") }
                .tint(.blue)
        }
        .contextMenu {
            Button { model.edit(summary) } label: { Label("box.edit.action", systemImage: "pencil") }
            Button(role: .destructive) { model.requestForget(summary) } label: {
                Label("namedProducts.forget", systemImage: "eraser")
            }
        }
    }
}
