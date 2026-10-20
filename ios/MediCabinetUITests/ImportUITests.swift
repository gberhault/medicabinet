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
    private let seed = #"[{"name":"Aspirin Test","category":"pain-fever","expiryInDays":200}]"#

    private func text(_ app: XCUIApplication, containing fragment: String) -> XCUIElement {
        app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] %@", fragment)).firstMatch
    }

    private func openCabinet(_ app: XCUIApplication) {
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 10))
        bar.buttons["Cabinet"].tap()
    }

    /// A file only this test writes, in a folder the app can read.
    private func temporaryFile(_ text: String) throws -> String {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).medicabinet")
        try Data(text.utf8).write(to: url)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url.path
    }

    func testThePreviewThenTheConfirmationReplaceTheCabinet() {
        let app = launch(opening: Self.golden, seed: seed)
        let replace = app.buttons["transfer.import.replace"]
        XCTAssertTrue(replace.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["File format: version 1"].exists)
        XCTAssertTrue(text(app, containing: "Importing replaces the whole cabinet").exists, "the replace statement")
        XCTAssertTrue(text(app, containing: "A backup of the current cabinet will be kept").exists)

        replace.tap()
        let confirm = app.buttons.matching(NSPredicate(format: "label == %@", "Replace")).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "a second, destructive confirmation")
        confirm.tap()

        XCTAssertTrue(app.staticTexts["Cabinet imported"].waitForExistence(timeout: 10))
        XCTAssertTrue(text(app, containing: "Automatic backups").exists, "says where the previous cabinet went")
        app.buttons["transfer.import.done.close"].tap()

        openCabinet(app)
        XCTAssertTrue(text(app, containing: "DOLIPRANE").waitForExistence(timeout: 10), "the imported cabinet, without a restart")
        XCTAssertFalse(text(app, containing: "Aspirin Test").exists)
    }

    func testCancelInThePreviewChangesNothing() {
        let app = launch(opening: Self.golden, seed: seed)
        let cancel = app.buttons["transfer.import.previewCancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 15))
        cancel.tap()

        openCabinet(app)
        XCTAssertTrue(text(app, containing: "Aspirin Test").waitForExistence(timeout: 10))
        XCTAssertFalse(text(app, containing: "DOLIPRANE").exists)
    }

    func testCancelInTheConfirmationChangesNothing() {
        let app = launch(opening: Self.golden, seed: seed)
        let replace = app.buttons["transfer.import.replace"]
        XCTAssertTrue(replace.waitForExistence(timeout: 15))
        replace.tap()
        let cancel = app.sheets.buttons["Cancel"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 5))
        cancel.tap()
        XCTAssertTrue(replace.waitForExistence(timeout: 5), "back on the preview")
        app.buttons["transfer.import.previewCancel"].tap()

        openCabinet(app)
        XCTAssertTrue(text(app, containing: "Aspirin Test").waitForExistence(timeout: 10))
    }

    func testAFileFromANewerVersionAsksToUpdateTheApp() throws {
        let path = try temporaryFile(#"{"archive":"medicabinet","formatVersion":99}"#)
        let app = launch(opening: path)
        let message = app.staticTexts["transfer.import.error.message"]
        XCTAssertTrue(message.waitForExistence(timeout: 15))
        XCTAssertEqual(message.label, "This file comes from a newer version of MediCabinet. Update the app, then try again.")
        XCTAssertFalse(app.buttons["transfer.import.retry"].exists, "a refused file cannot be retried")
        app.buttons["transfer.import.close"].tap()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 5))
    }
}
