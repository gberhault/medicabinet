import MediCabinetCore
import SwiftUI

/// What the import will do, before anything changes (I1, I2, I8, B3): the file, the phone, the replace and backup
/// statements; "Replace my cabinet" asks once more, Cancel being the default.
struct ImportPreviewView: View {
    let preview: ImportPreview
    let onReplace: () -> Void
    let onCancel: () -> Void
    @State private var confirming = false

    var body: some View {
        List {
            fileSection
            Section {
                CountLabel(key: "transfer.export.summary.boxes", count: preview.current.inCabinet, systemImage: "cross.case")
                CountLabel(key: "transfer.export.summary.locations", count: preview.current.locations, systemImage: "mappin.and.ellipse")
            } header: {
                Text("transfer.import.preview.currentTitle")
            }
            Section {
                TransferNotice(text: "transfer.import.preview.replaceStatement", systemImage: "arrow.triangle.2.circlepath")
                    .accessibilityIdentifier("transfer.import.preview.replaceStatement")
                if preview.willBackUp {
                    TransferNotice(text: "transfer.import.preview.backupStatement", systemImage: "externaldrive.badge.checkmark")
                        .accessibilityIdentifier("transfer.import.preview.backupStatement")
                } else {
                    TransferNotice(text: "transfer.import.preview.noBackupNeeded", systemImage: "info.circle")
                        .accessibilityIdentifier("transfer.import.preview.noBackupNeeded")
                }
            }
        }
        // Always in reach, whatever the text size: the decision is the point of this screen.
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                Button(role: .destructive) {
                    confirming = true
                } label: {
                    Label("transfer.import.replace", systemImage: "arrow.triangle.2.circlepath")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .accessibilityIdentifier("transfer.import.replace")
                Button(role: .cancel, action: onCancel) {
                    Text("transfer.import.cancel").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("transfer.import.previewCancel")
            }
            .padding()
            .background(.bar)
        }
        .confirmationDialog("transfer.import.confirm.title", isPresented: $confirming, titleVisibility: .visible) {
            Button("transfer.import.confirm.replace", role: .destructive, action: onReplace)
                .accessibilityIdentifier("transfer.import.confirm.replace")
            Button("transfer.import.cancel", role: .cancel) {}
        } message: {
            Text("transfer.import.confirm.message")
        }
    }

    private var fileSection: some View {
        let archive = preview.archive
        let file = preview.file
        return Section {
            Text(verbatim: TransferText.format("transfer.import.preview.exportedAt", TransferText.dateTime(archive.exportedAt)))
            Text(verbatim: TransferText.format(
                "transfer.import.preview.exportedBy", archive.app.name, archive.app.version, Self.platformName(archive.app.platform)
            ))
            Text(verbatim: TransferText.format("transfer.import.preview.formatVersion", archive.sourceFormatVersion))
                .accessibilityIdentifier("transfer.import.preview.formatVersion")
            CountLabel(key: "transfer.export.summary.boxes", count: file.inCabinet, systemImage: "cross.case")
                .accessibilityIdentifier("transfer.import.preview.boxes")
            CountLabel(key: "transfer.export.summary.removed", count: file.removed, systemImage: "clock.arrow.circlepath")
            CountLabel(key: "transfer.export.summary.locations", count: file.locations, systemImage: "mappin.and.ellipse")
            CountLabel(key: "transfer.import.preview.unknownExpiry", count: file.unknownExpiry, systemImage: "questionmark.circle")
        } header: {
            Text("transfer.import.preview.fileTitle")
        }
    }

    /// Platform names are proper nouns, the same in every language.
    static func platformName(_ platform: String) -> String {
        switch platform {
        case "ios": "iOS"
        case "android": "Android"
        default: platform
        }
    }
}

/// The import is done (I7): what the cabinet now holds and where the previous one went.
struct ImportResultView: View {
    let result: ImportResult
    let onClose: () -> Void

    var body: some View {
        List {
            Section {
                Label("transfer.import.done", systemImage: "checkmark.circle")
                    .font(.headline)
                    .accessibilityIdentifier("transfer.import.done")
                CountLabel(key: "transfer.export.summary.boxes", count: result.imported.inCabinet, systemImage: "cross.case")
                CountLabel(key: "transfer.export.summary.removed", count: result.imported.removed, systemImage: "clock.arrow.circlepath")
                CountLabel(key: "transfer.export.summary.locations", count: result.imported.locations, systemImage: "mappin.and.ellipse")
                if result.backup != nil {
                    Text("transfer.import.done.backupKept")
                        .accessibilityIdentifier("transfer.import.done.backupKept")
                }
            }
            Section {
                Button(action: onClose) {
                    Text("transfer.import.done.close").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("transfer.import.done.close")
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
        }
    }
}
