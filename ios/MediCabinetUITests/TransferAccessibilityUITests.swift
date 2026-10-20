import XCTest

/// Export, import and backups at the largest accessibility text size, in dark mode (X3): every control stays
/// reachable, the warning and the replace statement are text, not colour.
@MainActor
final class TransferAccessibilityUITests: XCTestCase {
    private let seed = #"[{"name":"Aspirin Test","category":"pain-fever","expiryInDays":200}]"#

    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(opening path: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-UITestingTemporaryStorage", "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
            "-AppleInterfaceStyle", "Dark"
        ] + (path.map { ["-UITestingOpenArchive", $0] } ?? [])
        app.launchEnvironment["MEDICABINET_UI_TEST_CABINET"] = seed
        app.launch()
        return app
    }

    /// Scrolls the list until the element is rendered (lists are lazy) and, for a control, can be tapped.
    private func reach(_ element: XCUIElement, in app: XCUIApplication, hittable: Bool = true) {
        var swipes = 0
        while !(element.exists && (!hittable || element.isHittable)) && swipes < 12 {
            app.collectionViews.firstMatch.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(element.exists, "never shown: \(element)")
        if hittable { XCTAssertTrue(element.isHittable, "not reachable: \(element)") }
    }

    func testTheExportSummaryAndItsWarningStayUsable() {
        let app = launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 15))
        app.tabBars.firstMatch.buttons["Settings"].tap()
        let export = app.buttons["transfer.export"]
        XCTAssertTrue(export.waitForExistence(timeout: 10))
        reach(export, in: app)
        export.tap()

        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] %@", "health data")).firstMatch
            .waitForExistence(timeout: 10))
        reach(app.buttons["transfer.export.continue"], in: app)
        reach(app.buttons["transfer.export.summaryCancel"], in: app)
        app.buttons["transfer.export.summaryCancel"].tap()
    }

    func testThePreviewKeepsItsDecisionInReach() {
        let app = launch(opening: ImportUITests.golden)
        let replace = app.buttons["transfer.import.replace"]
        XCTAssertTrue(replace.waitForExistence(timeout: 15))
        XCTAssertTrue(replace.isHittable, "pinned at the bottom, whatever the text size")
        XCTAssertTrue(app.buttons["transfer.import.previewCancel"].isHittable)
        XCTAssertGreaterThanOrEqual(replace.frame.height, 44)
        reach(app.staticTexts["transfer.import.preview.replaceStatement"], in: app, hittable: false)
    }

    func testTheBackupsListStaysUsable() {
        let app = launch(opening: ImportUITests.golden)
        let replace = app.buttons["transfer.import.replace"]
        XCTAssertTrue(replace.waitForExistence(timeout: 15))
        replace.tap()
        app.buttons.matching(NSPredicate(format: "label == %@", "Replace")).firstMatch.tap()
        let done = app.buttons["transfer.import.done.close"]
        XCTAssertTrue(done.waitForExistence(timeout: 15))
        XCTAssertTrue(done.isHittable, "pinned at the bottom, whatever the text size")
        done.tap()

        let gone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: app.otherElements["transfer.import.screen"])
        XCTAssertEqual(XCTWaiter().wait(for: [gone], timeout: 10), .completed)
        app.tabBars.firstMatch.buttons["Settings"].tap()
        let backups = app.buttons["transfer.backups"]
        XCTAssertTrue(app.buttons["transfer.export"].waitForExistence(timeout: 10))
        reach(backups, in: app)
        backups.tap()
        let restore = app.buttons["transfer.backups.restore"].firstMatch
        XCTAssertTrue(restore.waitForExistence(timeout: 10))
        reach(restore, in: app)
        XCTAssertGreaterThanOrEqual(restore.frame.height, 44)
    }
}
