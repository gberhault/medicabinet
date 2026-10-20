import MediCabinetCore
import SwiftUI

/// Settings → Backup and transfer: export the cabinet (E1), import one (O3), the automatic backups (B4).
struct TransferSection: View {
    let environment: AppEnvironment
    let transfer: CabinetTransfer
    @State private var export: ExportViewModel?
    @State private var picking = false
    @Environment(\.importCoordinator) private var importer

    var body: some View {
        Section {
            Button {
                let model = ExportViewModel(transfer: transfer, clock: environment.clock, timeZone: environment.timeZone)
                export = model
                // Not a view task: presenting the sheet can rebuild its content, which would cancel the read.
                Task { await model.start() }
            } label: {
                Label("transfer.export.action", systemImage: "square.and.arrow.up")
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            }
            .accessibilityIdentifier("transfer.export")
            .sheet(item: $export) { model in
                ExportSheet(model: model)
            }
            if let importer {
                Button {
                    picking = true
                } label: {
                    Label("transfer.import.action", systemImage: "square.and.arrow.down")
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }
                .accessibilityIdentifier("transfer.import")
                // JSON too: a file whose extension changed on the way is still offered; validation decides (§1.4).
                .fileImporter(isPresented: $picking, allowedContentTypes: [.medicabinetArchive, .json]) { result in
                    if case let .success(url) = result { importer.open(url) }
                }
                NavigationLink {
                    BackupsView(transfer: transfer, revision: environment.revision, onRestore: { importer.restore($0) })
                } label: {
                    Label("transfer.backups.action", systemImage: "clock.arrow.circlepath")
                        .frame(minHeight: 44)
                }
                .accessibilityIdentifier("transfer.backups")
            }
        } header: {
            Text("transfer.section.title")
        } footer: {
            Text("transfer.section.footer")
        }
    }
}

extension ExportViewModel: Identifiable {
    nonisolated var id: ObjectIdentifier { ObjectIdentifier(self) }
}
