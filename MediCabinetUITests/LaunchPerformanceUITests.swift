import XCTest

/// Measures app launch. Opt-in because it relaunches the app several times:
///   TEST_RUNNER_MEDICABINET_MEASURE_LAUNCH=1 xcodebuild … test -only-testing:MediCabinetUITests/LaunchPerformanceUITests
/// Simulator timing is noisy, so this reports a number (see reports/foundation/README.md); it is not a gate.
@MainActor
final class LaunchPerformanceUITests: XCTestCase {
    func testLaunchTime() throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["MEDICABINET_MEASURE_LAUNCH"] != nil,
            "set TEST_RUNNER_MEDICABINET_MEASURE_LAUNCH=1 to measure launch"
        )
        let app = XCUIApplication()
        app.launchArguments = ["-UITestingTemporaryStorage", "-AppleLanguages", "(en)"]
        measure(metrics: [XCTApplicationLaunchMetric()]) {
            app.launch()
            app.terminate()
        }
    }
}
