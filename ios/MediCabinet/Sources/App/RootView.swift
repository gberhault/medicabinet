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
        .task { await model.start() }
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
