import XCTest

/// Opening and importing a `.medicabinet` file (English), through the real screens. `-UITestingOpenArchive <path>`
/// hands the app a file the way Files or Mail would.
@MainActor
final class ImportUITests: XCTestCase {
    /// The repository's shared fixtures, read by the app on the simulator (it shares the Mac's file system).
    static let fixtures = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("fixtures", isDirectory: true)
    static let golden = fixtures.appendingPathComponent("archive/v1/golden.medicabinet").path

    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(opening path: String? = nil, seed: String? = nil, extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestingTemporaryStorage", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
            + (path.map { ["-UITestingOpenArchive", $0] } ?? []) + extra
        if let seed { app.launchEnvironment["MEDICABINET_UI_TEST_CABINET"] = seed }
        app.launch()
        return app
    }

    func testOpeningAnArchiveLandsOnTheImportFlow() {
        let app = launch(opening: Self.golden)
        XCTAssertTrue(app.navigationBars["Import a cabinet"].waitForExistence(timeout: 15))
        app.buttons["transfer.import.cancel"].tap()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["Import a cabinet"].exists)
    }
}
