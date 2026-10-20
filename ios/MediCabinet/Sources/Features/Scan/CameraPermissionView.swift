import SwiftUI

/// The states of the Scan tab where the camera is not showing. Every one offers manual entry.
struct ScanStatusView: View {
    enum Kind: Equatable {
        case needsPermission
        case denied(canOpenSettings: Bool)
        case unsupported
        case failed
    }

    let kind: Kind
    let allow: () -> Void
    let openSettings: () -> Void
    let retry: () -> Void
    let addManually: () -> Void

    var body: some View {
        ContentUnavailableView {
            switch kind {
            case .needsPermission: Label("scan.permission.title", systemImage: "camera")
            case .denied: Label("scan.permission.denied.title", systemImage: "camera.metering.unknown")
            case .unsupported: Label("scan.unsupported.title", systemImage: "barcode.viewfinder")
            case .failed: Label("scan.camera.failed.title", systemImage: "exclamationmark.triangle")
            }
        } description: {
            switch kind {
            case .needsPermission: Text("scan.permission.message")
            case .denied: Text("scan.permission.denied.message")
            case .unsupported: Text("scan.unsupported.message")
            case .failed: Text("scan.camera.failed.message")
            }
        } actions: {
            switch kind {
            case .needsPermission:
                Button("scan.permission.allow", action: allow)
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("scan.permission.allow")
            case let .denied(canOpenSettings):
                if canOpenSettings {
                    Button("scan.permission.settings", action: openSettings)
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("scan.permission.settings")
                }
            case .failed:
                Button("scan.camera.retry", action: retry)
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("scan.camera.retry")
            case .unsupported:
                EmptyView()
            }
            Button("scan.manual.open", action: addManually)
                .accessibilityIdentifier("scan.manual.open")
        }
    }
}
