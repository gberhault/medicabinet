import SwiftUI

struct RootView: View {
    let model: BootstrapModel
    let router: TabRouter
    let notifications: any NotificationClient

    var body: some View {
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
        .task {
            await model.start()
            // Health data must not linger: a previous export's temporary file, copies of files opened earlier.
            let files = ArchiveFiles.live
            await Task.detached(priority: .utility) { files.cleanUp() }.value
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
