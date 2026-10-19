import MediCabinetCore
import SwiftUI

/// A scanner that replays a script of payloads, for UI tests and previews: no camera involved.
@MainActor
final class InjectedCodeScanner: CodeScanner {
    /// Payloads for a UI test run, separated by `|` (write a group separator as `<GS>`).
    static let environmentKey = "MEDICABINET_UI_TEST_SCANS"

    let snapshots: AsyncStream<VisibleCodes>
    private let continuation: AsyncStream<VisibleCodes>.Continuation
    private let script: [String]
    /// The payloads this scanner will replay (tests).
    var scriptForTesting: [String] { script }
    private let interval: Duration
    private let clock: any Clock
    private var replay: Task<Void, Never>?

    init(script: [String], interval: Duration = .milliseconds(1_500), clock: any Clock) {
        self.script = script
        self.interval = interval
        self.clock = clock
        (snapshots, continuation) = AsyncStream.makeStream(of: VisibleCodes.self, bufferingPolicy: .unbounded)
    }

    /// `nil` when the UI-test environment variable is absent.
    static func fromEnvironment(_ environment: [String: String], clock: any Clock) -> InjectedCodeScanner? {
        guard let raw = environment[environmentKey] else { return nil }
        let payloads = raw.split(separator: "|").map { $0.replacingOccurrences(of: "<GS>", with: "\u{1D}") }
        return InjectedCodeScanner(script: payloads, clock: clock)
    }

    var preview: AnyView {
        AnyView(
            ZStack {
                Color.black
                Text(verbatim: "Simulated camera")
                    .foregroundStyle(.white)
            }
            .accessibilityHidden(true)
        )
    }

    func start() throws(CodeScannerError) {
        guard replay == nil else { return }
        replay = Task { [script, interval, clock, continuation] in
            for payload in script {
                continuation.yield(VisibleCodes(payloads: [payload], instant: clock.now))
                try? await Task.sleep(for: interval / 3)
                continuation.yield(VisibleCodes(payloads: [], instant: clock.now))
                try? await Task.sleep(for: interval)
                if Task.isCancelled { return }
            }
        }
    }

    func stop() {
        replay?.cancel()
        replay = nil
    }
}
