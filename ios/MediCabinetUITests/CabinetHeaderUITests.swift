import XCTest

/// The Cabinet header: the search field, the filter menu beside it and the filter chips.
@MainActor
final class CabinetHeaderUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication { CabinetUI.launch() }

    private func row(_ app: XCUIApplication, _ name: String) -> XCUIElement { CabinetUI.row(app, name) }

    func testTheSearchFieldAndTheFilterButtonShareOneRowUnderTheTitle() {
        let app = launch()
        let search = app.textFields["cabinet.search"]
        let button = app.buttons["cabinet.filter.button"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        XCTAssertTrue(button.exists)
        XCTAssertGreaterThan(button.frame.minX, search.frame.maxX - 1, "the button sits right of the field")
        XCTAssertEqual(button.frame.midY, search.frame.midY, accuracy: 6, "on the same line")
        XCTAssertFalse(app.buttons["cabinet.filter.chip"].exists, "no chip while no filter is on")
        XCTAssertFalse(app.staticTexts["cabinet.filter.summary"].exists, "the old filter bar is gone")
    }

    func testTheStatusAndLocationFiltersShowAChipAndEachChipClearsOnlyItsFilter() {
        let app = launch()
        app.buttons["cabinet.filter.button"].tap()
        app.buttons["Expired"].tap()
        let chip = app.buttons["cabinet.filter.chip"]
        XCTAssertTrue(chip.waitForExistence(timeout: 5))
        XCTAssertEqual(chip.label, "Remove filter Expired")
        XCTAssertEqual(app.buttons["cabinet.filter.button"].value as? String, "1 active filter")
        XCTAssertEqual(app.staticTexts["cabinet.count"].label, "1 box")
        XCTAssertFalse(row(app, "Spasfon").exists)

        chip.tap()
        XCTAssertTrue(row(app, "Spasfon").waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["cabinet.filter.chip"].exists)

        app.buttons["cabinet.filter.button"].tap()
        app.buttons["Car"].tap()
        XCTAssertTrue(row(app, "Gaviscon").waitForExistence(timeout: 5))
        XCTAssertFalse(row(app, "Spasfon").exists)
        XCTAssertEqual(app.buttons["cabinet.filter.chip"].label, "Remove filter Car")

        // A second filter: the chips are independent.
        app.buttons["cabinet.filter.button"].tap()
        app.buttons["Expiring soon"].tap()
        XCTAssertEqual(app.buttons.matching(identifier: "cabinet.filter.chip").count, 2)
        XCTAssertEqual(app.buttons["cabinet.filter.button"].value as? String, "2 active filters")
        app.buttons["Remove filter Car"].tap()
        XCTAssertEqual(app.buttons.matching(identifier: "cabinet.filter.chip").count, 1)
        XCTAssertEqual(app.buttons["cabinet.filter.chip"].label, "Remove filter Expiring soon")
    }

    func testClearFiltersInTheMenuClearsLocationAndStatusButKeepsTheSearch() {
        let app = launch()
        let search = app.textFields["cabinet.search"]
        search.tap()
        search.typeText("gav")
        app.buttons["cabinet.filter.button"].tap()
        app.buttons["Car"].tap()
        XCTAssertTrue(app.buttons["cabinet.filter.chip"].waitForExistence(timeout: 5))

        app.buttons["cabinet.filter.button"].tap()
        app.buttons["cabinet.filter.clear"].tap()
        XCTAssertFalse(app.buttons["cabinet.filter.chip"].waitForExistence(timeout: 2))
        XCTAssertEqual(search.value as? String, "gav")
        XCTAssertTrue(row(app, "Gaviscon").exists)
        XCTAssertFalse(row(app, "Spasfon").exists, "the search still narrows the list")
    }

    func testTheSearchFieldHasAClearButton() {
        let app = launch()
        let search = app.textFields["cabinet.search"]
        search.tap()
        search.typeText("spas")
        XCTAssertTrue(row(app, "Spasfon").waitForExistence(timeout: 5))
        app.buttons["cabinet.search.clear"].tap()
        XCTAssertTrue(row(app, "Thermometer").waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["cabinet.search.clear"].exists)
    }
}
