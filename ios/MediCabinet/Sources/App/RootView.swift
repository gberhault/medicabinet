import SwiftUI

struct RootView: View {
    let model: BootstrapModel
    let router: TabRouter
    let notifications: any NotificationClient
    let importer: ImportCoordinator

    var body: some View {
        @Bindable var importer = importer
        Group {
            switch model.state {
            case .loading:
                ProgressView {
                    Text("bootstrap.loading")
                }
                .accessibilityIdentifier("bootstrap.loading")
            case .failed(let failure):
                BootstrapErrorView(failure: failure) {
                    Task { await model.start() }
                }
            case .ready(let environment):
                MainTabView(environment: environment, router: router, notifications: notifications)
                    .environment(\.appEnvironment, environment)
            }
        }
        .environment(\.importCoordinator, importer)
        // A .medicabinet file opened from Files, Mail, AirDrop… (O1). It waits while the cabinet opens.
        .onOpenURL { importer.receive($0) }
        .onChange(of: readiness, initial: true) { _, readiness in
            switch readiness {
            case .ready: importer.cabinetOpened()
            case .unavailable: importer.cabinetUnavailable()
            case .loading: break
            }
        }
        .alert("transfer.error.title", isPresented: $importer.showsUnavailable) {
            Button("transfer.error.close", role: .cancel) {}
        } message: {
            Text("transfer.import.unavailable")
        }
        .task {
            if let opened = await AppLaunch.uiTestOpenedArchive() { importer.receive(opened) }
            await model.start()
            // Health data must not linger: a previous export's temporary file, copies of files opened earlier.
            // A file that is waiting to be imported is kept.
            let (files, held) = (ArchiveFiles.live, importer.heldFiles)
            await Task.detached(priority: .utility) { files.cleanUp(keeping: held) }.value
        }
    }

    private var readiness: ImportCoordinator.Readiness {
        switch model.state {
        case .loading: .loading
        case .ready: .ready
        case .failed: .unavailable
        }
    }
}

private struct AppEnvironmentKey: EnvironmentKey {
    static let defaultValue: AppEnvironment? = nil
}

extension EnvironmentValues {
    /// Set once bootstrap succeeded; `nil` only before that.
    var appEnvironment: AppEnvironment? {
        get { self[AppEnvironmentKey.self] }
        set { self[AppEnvironmentKey.self] = newValue }
    }
}
