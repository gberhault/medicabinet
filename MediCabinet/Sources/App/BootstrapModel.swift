import Observation

/// Presentation state of app launch: loading → ready, or a blocking failure with Retry.
@MainActor
@Observable
final class BootstrapModel {
    enum State {
        case loading
        case ready(AppEnvironment)
        case failed(BootstrapFailure)
    }

    private(set) var state: State = .loading
    private let run: @Sendable () async -> BootstrapState

    init(run: @escaping @Sendable () async -> BootstrapState) {
        self.run = run
    }

    /// Runs the bootstrap; also used by Retry. Ignored while a run is in progress.
    func start() async {
        if case .ready = state { return }
        state = .loading
        switch await run() {
        case .ready(let environment): state = .ready(environment)
        case .failed(let failure): state = .failed(failure)
        }
    }
}
