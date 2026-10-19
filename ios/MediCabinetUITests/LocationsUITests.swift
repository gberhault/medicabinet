import XCTest

/// Settings → Locations on a fresh cabinet (English), through the real screens.
@MainActor
final class LocationsUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func openLocations(_ app: XCUIApplication) {
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15))
        bar.buttons["Settings"].tap()
        app.buttons["settings.locations"].tap()
        XCTAssertTrue(app.buttons["locations.add"].waitForExistence(timeout: 5))
    }

    private func add(_ name: String, in app: XCUIApplication) {
        app.buttons["locations.add"].tap()
        let field = app.textFields["locations.name.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText(name)
        app.buttons["locations.name.save"].tap()
    }

    func testTheFreshCabinetHasItsDefaultLocationMarkedAsDefault() {
        let app = UITestApp.launch()
        openLocations(app)
        XCTAssertTrue(app.staticTexts["Medicine cabinet"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Default"].exists, "the default is marked with text, not colour alone")
    }

    func testAddingALocationShowsItInTheList() {
        let app = UITestApp.launch()
        openLocations(app)
        add("Garage", in: app)
        XCTAssertTrue(app.staticTexts["Garage"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Medicine cabinet"].exists)
    }

    func testARefusedNameExplainsWhyAndKeepsTheSheetOpen() {
        let app = UITestApp.launch()
        openLocations(app)
        add("medicine CABINET", in: app)
        let problem = app.staticTexts["locations.name.problem"]
        XCTAssertTrue(problem.waitForExistence(timeout: 5))
        XCTAssertEqual(problem.label, "A location already has this name.")
        XCTAssertTrue(app.textFields["locations.name.field"].exists, "the sheet stays open to fix the name")
    }

    func testDeletingAnEmptyLocationOffersAnUndoAboveTheTabBar() {
        let app = UITestApp.launch()
        openLocations(app)
        add("Garage", in: app)
        let row = app.staticTexts["Garage"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.swipeLeft()
        app.buttons["Delete"].tap()

        let message = app.staticTexts["undo.message"]
        XCTAssertTrue(message.waitForExistence(timeout: 5))
        XCTAssertEqual(message.label, "Location deleted: Garage")
        XCTAssertFalse(app.staticTexts["Garage"].exists, "the location is gone while the banner shows")
        let undo = app.buttons["undo.action"]
        XCTAssertLessThanOrEqual(undo.frame.maxY, app.tabBars.firstMatch.frame.minY, "the banner sits above the tab bar")

        undo.tap()
        XCTAssertTrue(app.staticTexts["Garage"].waitForExistence(timeout: 5), "Undo brings the location back")
        XCTAssertFalse(app.staticTexts["undo.message"].exists)
    }

    func testTheDefaultLocationOffersNoDeleteAndSaysWhy() {
        let app = UITestApp.launch()
        openLocations(app)
        app.staticTexts["Medicine cabinet"].swipeLeft()
        XCTAssertFalse(app.buttons["Delete"].exists)
        XCTAssertTrue(app.staticTexts["To delete the default location, choose another default location first."].exists)
    }

    func testTheScreenStaysUsableAtTheLargestTextSize() {
        let app = UITestApp.launch(extraArguments: [
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
            "-AppleInterfaceStyle", "Dark"
        ])
        openLocations(app)
        XCTAssertTrue(app.buttons["locations.add"].isHittable)
        add("Garage", in: app)
        XCTAssertTrue(app.staticTexts["Garage"].waitForExistence(timeout: 5))
    }
}
