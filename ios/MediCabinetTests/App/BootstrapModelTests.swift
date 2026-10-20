import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@MainActor
@Suite struct BootstrapModelTests {
    private func environment() -> AppEnvironment {
        let store = InMemoryCabinetStore(clock: SystemClock())
        return AppEnvironment(
            locations: store, boxes: store, named: store.named, clock: SystemClock(),
            reference: InMemoryReferenceData(status: .unavailable(.missing))
        )
    }

    private final class Script: @unchecked Sendable {
        private let lock = NSLock()
        private var results: [BootstrapState]
        private(set) var calls = 0
        init(_ results: [BootstrapState]) { self.results = results }
        func next() -> BootstrapState {
            lock.lock()
            defer { lock.unlock() }
            calls += 1
            return results.count > 1 ? results.removeFirst() : results[0]
        }
    }

    @Test func startsInLoading() {
        let model = BootstrapModel { .failed(.storage) }
        guard case .loading = model.state else { Issue.record("expected loading"); return }
    }

    @Test func becomesReadyWhenBootstrapSucceeds() async {
        let env = environment()
        let model = BootstrapModel { .ready(env) }
        await model.start()
        guard case .ready = model.state else { Issue.record("expected ready"); return }
    }

    @Test(arguments: [BootstrapFailure.storage, .backup, .migration])
    func exposesTheFailureKind(failure: BootstrapFailure) async {
        let model = BootstrapModel { .failed(failure) }
        await model.start()
        guard case .failed(let shown) = model.state else { Issue.record("expected failed"); return }
        #expect(shown == failure)
    }

    @Test func retryAfterAFailureCanSucceed() async {
        let script = Script([.failed(.migration), .ready(environment())])
        let model = BootstrapModel { script.next() }
        await model.start()
        guard case .failed = model.state else { Issue.record("expected failed first"); return }
        await model.start()
        guard case .ready = model.state else { Issue.record("expected ready after retry"); return }
        #expect(script.calls == 2)
    }

    @Test func startingAgainOnceReadyDoesNotRunBootstrapTwice() async {
        let script = Script([.ready(environment())])
        let model = BootstrapModel { script.next() }
        await model.start()
        await model.start()
        #expect(script.calls == 1)
    }
}
