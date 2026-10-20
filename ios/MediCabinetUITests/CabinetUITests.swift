import XCTest

/// The Cabinet tab, box detail and edit on a seeded cabinet (English), through the real screens.
@MainActor
final class CabinetUITests: XCTestCase {
    /// Statuses are relative to today, so the seed never rots: Doliprane x2 (one expired, one ok), Spasfon (ok),
    /// a thermometer with no expiry, Gaviscon in the car (expiring soon).
    private let seed = """
    [{"name":"Doliprane 1000","gtin":"03400930000014","category":"pain-fever","expiryInDays":-5,"serial":"A1"},
     {"name":"Doliprane 1000","gtin":"03400930000014","category":"pain-fever","expiryInDays":500,"serial":"A2","opened":true,"lot":"LOT9"},
     {"name":"Spasfon","gtin":"03400930000021","category":"digestion","expiryInDays":400},
     {"name":"Thermometer","category":"first-aid"},
     {"name":"Gaviscon","gtin":"03400930000038","category":"digestion","expiryInDays":20,"location":"Car"}]
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
        bar.buttons["Cabinet"].tap()
        return app
    }

    /// A static text by its visible text (identifiers set on a container would hide it from `staticTexts["…"]`).
    private func text(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label ==[c] %@ OR label ENDSWITH[c] %@", label, ", " + label)).firstMatch
    }

    private func row(_ app: XCUIApplication, _ name: String) -> XCUIElement {
        app.cells.containing(.staticText, identifier: name).firstMatch
    }

    // MARK: List

    func testRowsAreGroupedByCategoryWithCountAndStatusAsText() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["cabinet.count"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["cabinet.count"].label, "5 boxes")
        XCTAssertTrue(text(app, "Pain and fever").exists)
        XCTAssertTrue(text(app, "Digestion").exists)
        XCTAssertTrue(text(app, "First-aid kit").exists)
        let doliprane = row(app, "Doliprane 1000")
        XCTAssertTrue(doliprane.exists)
        XCTAssertEqual(doliprane.staticTexts.matching(NSPredicate(format: "label BEGINSWITH '2 boxes'")).count, 1)
        XCTAssertTrue(doliprane.staticTexts["Expired"].exists, "the status is text, not colour alone")
        XCTAssertTrue(row(app, "Thermometer").staticTexts["Expiry unknown"].exists, "an unknown expiry is never OK")
        XCTAssertFalse(row(app, "Thermometer").staticTexts["OK"].exists)
    }

    func testSearchNarrowsTheListAndAnUnmatchedSearchOffersClear() {
        let app = launch()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("spas")
        XCTAssertTrue(row(app, "Spasfon").waitForExistence(timeout: 5))
        XCTAssertFalse(row(app, "Thermometer").exists)
        XCTAssertEqual(app.staticTexts["cabinet.count"].label, "1 box")

        search.clearAndType("zzzz")
        XCTAssertTrue(text(app, "No results").waitForExistence(timeout: 5))
        app.buttons["cabinet.noResults.clear"].tap()
        XCTAssertTrue(row(app, "Thermometer").waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["cabinet.count"].label, "5 boxes")
    }

    func testTheStatusAndLocationFiltersShowTheirActiveStateAndClear() {
        let app = launch()
        app.buttons["cabinet.filter.status"].tap()
        app.buttons["Expired"].tap()
        XCTAssertTrue(app.staticTexts["cabinet.filter.summary"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["cabinet.filter.summary"].label, "Filters: Expired")
        XCTAssertEqual(app.staticTexts["cabinet.count"].label, "1 box")
        XCTAssertFalse(row(app, "Spasfon").exists)

        app.buttons["cabinet.filter.clear"].tap()
        XCTAssertTrue(row(app, "Spasfon").waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["cabinet.filter.summary"].exists)

        app.buttons["cabinet.filter.location"].tap()
        app.buttons["Car"].tap()
        XCTAssertTrue(row(app, "Gaviscon").waitForExistence(timeout: 5))
        XCTAssertFalse(row(app, "Spasfon").exists)
        XCTAssertEqual(app.staticTexts["cabinet.filter.summary"].label, "Filters: Car")
    }

    func testAnEmptyCabinetExplainsItselfAndTheButtonOpensTheScanTab() {
        let app = launch(seeded: false)
        XCTAssertTrue(text(app, "The cabinet is empty").waitForExistence(timeout: 10))
        app.buttons["cabinet.empty.scan"].tap()
        XCTAssertTrue(app.buttons["scan.manual.open"].waitForExistence(timeout: 10) || app.buttons["scan.count"].exists
                      || app.staticTexts["Camera turned off"].exists || app.buttons["scan.permission.allow"].exists
                      || app.staticTexts["Scanning unavailable"].exists)
        XCTAssertTrue(app.tabBars.firstMatch.buttons["Scan"].isSelected)
    }

    // MARK: Product boxes and detail

    func testARowWithSeveralBoxesListsThemEarliestFirstAndOpensTheirDetail() {
        let app = launch()
        row(app, "Doliprane 1000").tap()
        let boxes = app.buttons.matching(identifier: "product.box")
        XCTAssertTrue(boxes.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(boxes.count, 2)
        XCTAssertTrue(boxes.element(boundBy: 0).staticTexts["Expired"].exists, "earliest expiry first")
        XCTAssertTrue(boxes.element(boundBy: 1).staticTexts["Opened"].exists)
        XCTAssertTrue(boxes.element(boundBy: 1).staticTexts["Medicine cabinet"].exists)
        boxes.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["box.name"].waitForExistence(timeout: 5))
    }

    func testARowWithOneBoxGoesStraightToItsDetailWhichNeverShowsTheSerial() {
        let app = launch()
        row(app, "Spasfon").tap()
        XCTAssertTrue(app.staticTexts["box.name"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["box.name"].label, "Spasfon")
        XCTAssertTrue(text(app, "3400930000021").exists, "the 13-digit code")
        XCTAssertTrue(text(app, "Digestion").exists)
        XCTAssertTrue(text(app, "OK").exists)
        XCTAssertFalse(text(app, "A1").exists)
    }

    func testABoxWithoutACodeSaysNoCodeAndAnUnknownExpiry() {
        let app = launch()
        row(app, "Thermometer").tap()
        XCTAssertTrue(app.staticTexts["box.name"].waitForExistence(timeout: 5))
        XCTAssertTrue(text(app, "No code").exists)
        XCTAssertTrue(text(app, "Expiry unknown").exists)
    }

    // MARK: Taking out, undo

    func testUsedUpLeavesTheScreenAndUndoBringsTheBoxBack() {
        let app = launch()
        row(app, "Spasfon").tap()
        app.buttons["box.remove.usedUp"].tap()
        let message = app.staticTexts["undo.message"]
        XCTAssertTrue(message.waitForExistence(timeout: 5))
        XCTAssertEqual(message.label, "Spasfon: used up")
        XCTAssertTrue(row(app, "Thermometer").waitForExistence(timeout: 5), "back on the Cabinet list")
        XCTAssertFalse(row(app, "Spasfon").exists)

        app.buttons["undo.action"].tap()
        XCTAssertTrue(row(app, "Spasfon").waitForExistence(timeout: 5))
    }

    func testAddedByMistakeIsExplainedAndUndoable() {
        let app = launch()
        row(app, "Gaviscon").tap()
        XCTAssertTrue(text(app, "Deletes the box without counting it as used up or thrown away.").waitForExistence(timeout: 5))
        app.buttons["box.remove.mistake"].tap()
        XCTAssertEqual(app.staticTexts["undo.message"].label, "Gaviscon: deleted")
        XCTAssertFalse(row(app, "Gaviscon").exists)
        app.buttons["undo.action"].tap()
        XCTAssertTrue(row(app, "Gaviscon").waitForExistence(timeout: 5))
    }

    func testTakingOutTheLastBoxOfAListedRowReturnsToTheCabinet() {
        let app = launch(extra: [])
        row(app, "Doliprane 1000").tap()
        app.buttons.matching(identifier: "product.box").element(boundBy: 0).tap()
        app.buttons["box.remove.thrownAway"].tap()
        // One box is left: still on the product's list.
        XCTAssertTrue(app.buttons.matching(identifier: "product.box").firstMatch.waitForExistence(timeout: 5))
        app.buttons.matching(identifier: "product.box").firstMatch.tap()
        app.buttons["box.remove.usedUp"].tap()
        XCTAssertTrue(row(app, "Thermometer").waitForExistence(timeout: 5), "P3: back on the Cabinet list")
    }

    // MARK: Edit and category

    func testEditingTheNameSavesIt() {
        let app = launch()
        row(app, "Spasfon").tap()
        app.buttons["box.edit"].tap()
        let name = app.textFields["box.edit.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        name.clearAndType("Spasfon Lyoc")
        app.buttons["box.edit.save"].tap()
        XCTAssertTrue(app.staticTexts["Spasfon Lyoc"].waitForExistence(timeout: 5))
    }

    func testAnEmptyNameCannotBeSavedAndExplainsWhy() {
        let app = launch()
        row(app, "Spasfon").tap()
        app.buttons["box.edit"].tap()
        let name = app.textFields["box.edit.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        name.clearAndType("")
        XCTAssertTrue(text(app, "The name can't be empty.").waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["box.edit.save"].isEnabled)
        app.buttons["box.edit.cancel"].tap()
        XCTAssertEqual(app.staticTexts["box.name"].label, "Spasfon", "Cancel discards")
    }

    func testChoosingACategoryMarksItSetByYouAndAutomaticGivesItBack() {
        let app = launch()
        row(app, "Thermometer").tap()
        app.buttons["box.edit"].tap()
        let picker = app.buttons["box.edit.category"]
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        picker.tap()
        let eyes = app.buttons["Eyes"]
        XCTAssertTrue(eyes.waitForExistence(timeout: 5))
        eyes.tap()
        XCTAssertTrue(app.staticTexts["box.edit.category.manual"].waitForExistence(timeout: 5))
        app.buttons["box.edit.save"].tap()
        XCTAssertTrue(text(app, "Eyes").waitForExistence(timeout: 5))
        XCTAssertTrue(text(app, "Set by you").exists, "\"Set by you\" marker")

        app.buttons["box.edit"].tap()
        XCTAssertTrue(app.staticTexts["box.edit.category.manual"].waitForExistence(timeout: 5), "marked before the tap")
        app.buttons["box.edit.category.automatic"].tap()
        XCTAssertFalse(app.staticTexts["box.edit.category.manual"].waitForExistence(timeout: 2))
        app.buttons["box.edit.save"].tap()
        XCTAssertTrue(text(app, "Uncategorized").waitForExistence(timeout: 5), "no code: the classifier gives uncategorized")
        XCTAssertFalse(text(app, "Set by you").exists, "the marker is gone once the classifier decides")
    }

    func testDateNotPrintedIsOfferedWhenEditingAnUnknownExpiry() {
        let app = launch()
        row(app, "Thermometer").tap()
        app.buttons["box.edit"].tap()
        XCTAssertTrue(app.switches["expiry.notPrinted"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.switches["expiry.notPrinted"].value as? String, "1", "unknown stays unknown, nothing pre-filled")
        XCTAssertTrue(app.buttons["box.edit.save"].isEnabled)
    }

    // MARK: Accessibility

    func testTheScreensStayUsableAtTheLargestTextSizeInDarkMode() {
        let app = launch(extra: [
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
            "-AppleInterfaceStyle", "Dark"
        ])
        XCTAssertTrue(app.staticTexts["cabinet.count"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["cabinet.filter.status"].exists)
        XCTAssertTrue(app.buttons["cabinet.filter.location"].exists)
        let spasfon = row(app, "Spasfon")
        for _ in 0..<8 where !(spasfon.exists && spasfon.isHittable) { app.swipeUp() }
        XCTAssertTrue(spasfon.isHittable, "rows stay reachable (and wrap) at the largest text size")
        spasfon.tap()
        XCTAssertTrue(app.buttons["box.edit"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["box.edit"].isHittable)
    }
}

private extension XCUIElement {
    func clearAndType(_ text: String) {
        tap()
        if let current = value as? String, !current.isEmpty {
            typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        if !text.isEmpty { typeText(text) }
    }
}
