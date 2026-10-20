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
    static let goldenVersion2 = fixtures.appendingPathComponent("archive/v2/golden.medicabinet").path

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
        openTab("Cabinet", in: app)
    }

    /// Once the import flow has slid away.
    private func openTab(_ name: String, in app: XCUIApplication) {
        let gone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: app.otherElements["transfer.import.screen"])
        XCTAssertEqual(XCTWaiter().wait(for: [gone], timeout: 10), .completed, "the import flow closes")
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 10))
        bar.buttons[name].tap()
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

    /// A version 2 file lists the products the household named; a version 1 file has none and shows no such line.
    func testThePreviewCountsTheProductsYouNamedOfAVersionTwoFileOnly() {
        let app = launch(opening: Self.goldenVersion2)
        XCTAssertTrue(app.buttons["transfer.import.replace"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["File format: version 2"].exists)
        let line = app.staticTexts["transfer.import.preview.namedProducts"]
        XCTAssertTrue(line.exists)
        XCTAssertEqual(line.label, "Products you named: 2", "the live ones only: the forgotten one is not counted")
        app.terminate()

        let old = launch(opening: Self.golden)
        XCTAssertTrue(old.buttons["transfer.import.replace"].waitForExistence(timeout: 15))
        XCTAssertFalse(old.staticTexts["transfer.import.preview.namedProducts"].exists)
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
    func testRestoringTheAutomaticBackupBringsTheFirstCabinetBack() {
        let app = launch(opening: Self.golden, seed: seed)
        let replace = app.buttons["transfer.import.replace"]
        XCTAssertTrue(replace.waitForExistence(timeout: 15))
        replace.tap()
        app.buttons.matching(NSPredicate(format: "label == %@", "Replace")).firstMatch.tap()
        XCTAssertTrue(app.buttons["transfer.import.done.close"].waitForExistence(timeout: 10))
        app.buttons["transfer.import.done.close"].tap()

        openTab("Settings", in: app)
        let backups = app.buttons["transfer.backups"]
        XCTAssertTrue(backups.waitForExistence(timeout: 5))
        backups.tap()
        XCTAssertTrue(app.staticTexts["Before an import"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["1 box"].exists, "the backup holds the seeded cabinet")
        app.buttons["transfer.backups.restore"].firstMatch.tap()

        let restore = app.buttons["transfer.import.replace"]
        XCTAssertTrue(restore.waitForExistence(timeout: 15))
        XCTAssertTrue(text(app, containing: "Restore the backup of").exists, "the preview says it is a restore")
        restore.tap()
        app.buttons.matching(NSPredicate(format: "label == %@", "Replace")).firstMatch.tap()
        XCTAssertTrue(app.buttons["transfer.import.done.close"].waitForExistence(timeout: 10))
        app.buttons["transfer.import.done.close"].tap()

        XCTAssertTrue(app.staticTexts["Before a restore"].waitForExistence(timeout: 10), "the restore backed up the import")
        openCabinet(app)
        XCTAssertTrue(text(app, containing: "Aspirin Test").waitForExistence(timeout: 10))
        XCTAssertFalse(text(app, containing: "DOLIPRANE").exists)
    }
}
