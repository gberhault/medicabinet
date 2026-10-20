import XCTest

/// Settings → Products you named, through the real screens (English), on a seeded cabinet.
@MainActor
final class NamedProductsUITests: XCTestCase {
    private static let named = """
    [{"gtin":"03401900000003","name":"Arnica","category":"skin-wound"},{"gtin":"03401900000010","name":"Zinc"}]
    """

    /// Two boxes of Arnica in the cabinet (one used up is not seeded: the seed has no removal), none of Zinc.
    private static let boxes = """
    [{"name":"Arnica","gtin":"03401900000003","category":"skin-wound","expiryInDays":300,"serial":"A1"},
     {"name":"Arnica","gtin":"03401900000003","category":"skin-wound","expiryInDays":310,"serial":"A2"}]
    """

    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(named: String? = NamedProductsUITests.named, boxes: String? = NamedProductsUITests.boxes,
                        extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-UITestingTemporaryStorage", "-UITestNotifications", "notDetermined", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"
        ] + extra
        if let named { app.launchEnvironment["MEDICABINET_UI_TEST_NAMED_PRODUCTS"] = named }
        if let boxes { app.launchEnvironment["MEDICABINET_UI_TEST_CABINET"] = boxes }
        app.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 15))
        return app
    }

    private func openList(_ app: XCUIApplication) {
        app.tabBars.firstMatch.buttons["Settings"].tap()
        let row = app.buttons["settings.namedProducts"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
    }

    private func rows(_ app: XCUIApplication) -> XCUIElementQuery {
        app.buttons.matching(identifier: "namedProducts.row")
    }

    func testTheSettingsRowOpensTheListInNameOrderWithCodesCategoriesAndCounts() {
        let app = launch()
        openList(app)
        XCTAssertTrue(app.navigationBars["Products you named"].waitForExistence(timeout: 5))
        let arnica = rows(app).element(boundBy: 0)
        let zinc = rows(app).element(boundBy: 1)
        XCTAssertTrue(arnica.waitForExistence(timeout: 5))
        XCTAssertTrue(arnica.label.contains("Arnica"))
        XCTAssertTrue(arnica.label.contains("3401900000003"), "the code as box detail shows it")
        XCTAssertTrue(arnica.label.contains("2 boxes in the cabinet"), "the count is text")
        XCTAssertTrue(zinc.label.contains("Zinc"))
        XCTAssertTrue(zinc.label.contains("Not in the cabinet"))
        XCTAssertTrue(zinc.label.contains("Uncategorized"))
        XCTAssertLessThan(arnica.frame.minY, zinc.frame.minY, "ordered by name")
        XCTAssertGreaterThanOrEqual(arnica.frame.height, 44)
        XCTAssertGreaterThanOrEqual(zinc.frame.height, 44)
    }

    func testWithoutNamedProductsTheListExplainsWhenProductsAppearHere() {
        let app = launch(named: nil, boxes: nil)
        openList(app)
        XCTAssertTrue(app.otherElements["namedProducts.empty"].waitForExistence(timeout: 5)
            || app.staticTexts["No named product"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["When you scan a product the medicines database does not know, the name you give it appears here."]
            .exists)
    }

    func testRenamingAProductShowsTheNewNameInTheCabinetTab() {
        let app = launch()
        openList(app)
        rows(app).element(boundBy: 0).tap()
        let name = app.textFields["namedProducts.edit.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        XCTAssertEqual(name.value as? String, "Arnica", "pre-filled")
        name.clearAndType("Arnica gel")
        app.buttons["namedProducts.edit.save"].tap()
        XCTAssertTrue(rows(app).element(boundBy: 0).waitForExistence(timeout: 5))
        XCTAssertTrue(rows(app).element(boundBy: 0).label.contains("Arnica gel"))

        app.tabBars.firstMatch.buttons["Cabinet"].tap()
        XCTAssertTrue(CabinetUI.row(app, "Arnica gel").waitForExistence(timeout: 5), "the boxes took the new name")
    }

    func testAnEmptyNameCannotBeSavedAndExplainsWhy() {
        let app = launch()
        openList(app)
        rows(app).element(boundBy: 0).tap()
        let name = app.textFields["namedProducts.edit.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        name.clearAndType("")
        XCTAssertTrue(app.staticTexts["The name can't be empty."].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["namedProducts.edit.save"].isEnabled)
        app.buttons["namedProducts.edit.cancel"].tap()
        XCTAssertTrue(rows(app).element(boundBy: 0).label.contains("Arnica"), "Cancel discards")
    }

    func testTheEditFormSaysItAppliesToEveryBoxAndAutomaticCategoryIsThere() {
        let app = launch()
        openList(app)
        rows(app).element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts[
            "The name and category apply to every box of this product, including used-up and thrown-away ones."
        ].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["namedProducts.edit.category.automatic"].exists)
        XCTAssertTrue(app.buttons["namedProducts.edit.category"].exists)
    }

    func testForgettingAsksForConfirmationThenOffersUndoAboveTheTabBar() {
        let app = launch()
        openList(app)
        rows(app).element(boundBy: 0).swipeLeft()
        app.buttons["Forget"].tap()
        XCTAssertTrue(app.staticTexts["Forget “Arnica”?"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts[
            "2 boxes are still in the cabinet and keep their name. The next scan of this code will ask you for a name."
        ].exists)
        app.buttons["namedProducts.forget.confirm"].tap()

        let message = app.staticTexts["undo.message"]
        XCTAssertTrue(message.waitForExistence(timeout: 5))
        XCTAssertEqual(message.label, "“Arnica” forgotten")
        XCTAssertEqual(rows(app).count, 1, "the row left the list")
        let undo = app.buttons["undo.action"]
        XCTAssertLessThanOrEqual(undo.frame.maxY, app.tabBars.firstMatch.frame.minY, "the banner sits above the tab bar")

        undo.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Arnica'")).firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(rows(app).count, 2)
    }

    func testCancellingTheConfirmationChangesNothing() {
        let app = launch()
        openList(app)
        rows(app).element(boundBy: 1).press(forDuration: 1)
        app.buttons["Forget"].tap()
        XCTAssertTrue(app.staticTexts["The next scan of this code will ask you for a name."].waitForExistence(timeout: 5))
        app.buttons["Cancel"].tap()
        XCTAssertEqual(rows(app).count, 2)
        XCTAssertFalse(app.staticTexts["undo.message"].exists)
    }

    func testTheScreenStaysUsableAtTheLargestTextSizeInDarkMode() {
        let app = launch(extra: [
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL", "-AppleInterfaceStyle", "Dark"
        ])
        openList(app)
        let first = rows(app).element(boundBy: 0)
        XCTAssertTrue(first.waitForExistence(timeout: 5))
        XCTAssertTrue(first.isHittable)
        first.tap()
        let name = app.textFields["namedProducts.edit.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["namedProducts.edit.cancel"].isHittable)
    }
}

private extension XCUIElement {
    /// Empties a text field (one delete key per character) and types `text`.
    func clearAndType(_ text: String) {
        tap()
        if let current = value as? String, !current.isEmpty {
            typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        if !text.isEmpty { typeText(text) }
    }
}
