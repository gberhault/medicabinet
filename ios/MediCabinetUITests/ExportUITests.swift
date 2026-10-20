import XCTest

/// Settings → Export the cabinet (English), through the real screens and the system share sheet.
@MainActor
final class ExportUITests: XCTestCase {
    private let seed = """
    [{"name":"Doliprane 1000","gtin":"03400930000014","category":"pain-fever","expiryInDays":300,"serial":"A1"},
     {"name":"Thermometer","category":"first-aid"}]
    """

    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(seeded: Bool = true, extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestingTemporaryStorage", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extra
        if seeded { app.launchEnvironment["MEDICABINET_UI_TEST_CABINET"] = seed }
        app.launch()
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15))
        bar.buttons["Settings"].tap()
        return app
    }

    private func openExport(_ app: XCUIApplication) {
        let export = app.buttons["transfer.export"]
        XCTAssertTrue(export.waitForExistence(timeout: 5))
        export.tap()
    }

    func testTheCountsAndTheHealthDataWarningComeBeforeTheShareSheet() {
        let app = launch()
        openExport(app)

        let warning = app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] %@", "health data")).firstMatch
        XCTAssertTrue(warning.waitForExistence(timeout: 10), "the warning is shown first")
        XCTAssertTrue(app.staticTexts["2 boxes in the cabinet"].exists)
        XCTAssertTrue(app.staticTexts["1 location"].exists)
        XCTAssertFalse(app.otherElements["ActivityListView"].exists, "no share sheet before Continue")

        app.buttons["transfer.export.continue"].tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 10), "the share sheet opens after Continue")
    }

    func testCancelClosesWithoutExporting() {
        let app = launch()
        openExport(app)
        let cancel = app.buttons["transfer.export.summaryCancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 10), app.debugDescription)
        cancel.tap()
        XCTAssertTrue(app.buttons["transfer.export"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["transfer.export.continue"].exists)
        XCTAssertFalse(app.otherElements["ActivityListView"].exists)
    }

    func testAnEmptyCabinetSaysTheFileHoldsOnlyTheLocations() {
        let app = launch(seeded: false)
        openExport(app)
        XCTAssertTrue(app.staticTexts["transfer.export.summary.empty"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["0 boxes in the cabinet"].exists)
    }
}
