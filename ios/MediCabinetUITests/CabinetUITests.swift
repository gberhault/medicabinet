import XCTest

/// The Cabinet tab, box detail and edit on a seeded cabinet (English), through the real screens.
@MainActor
final class CabinetUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(seeded: Bool = true, extra: [String] = []) -> XCUIApplication {
        CabinetUI.launch(seeded: seeded, extra: extra)
    }

    private func text(_ app: XCUIApplication, _ label: String) -> XCUIElement { CabinetUI.text(app, label) }

    private func row(_ app: XCUIApplication, _ name: String) -> XCUIElement { CabinetUI.row(app, name) }

    // MARK: List

    func testCardsAreAlphabeticalAndPillsShowShortNameCountAndOnlyAlertStatuses() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["cabinet.count"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["cabinet.count"].label, "6 boxes")

        let headers = app.descendants(matching: .any).matching(identifier: "cabinet.card.header")
            .allElementsBoundByIndex.sorted { $0.frame.minY < $1.frame.minY }
        XCTAssertEqual(headers.count, 3)
        XCTAssertTrue(headers[0].label.hasPrefix("Digestion"), "A to Z: Digestion first")
        XCTAssertTrue(headers[0].label.contains("3 boxes"))
        XCTAssertTrue(headers[1].label.hasPrefix("First-aid kit"))
        XCTAssertTrue(headers[2].label.hasPrefix("Pain and fever"))

        // The spoken label has the full name; the eye reads the short one, in A to Z order within the card.
        XCTAssertTrue(row(app, "Smecta 3 g, poudre pour suspension buvable, 1 box").exists)
        XCTAssertEqual(row(app, "Spasfon").label, "Spasfon, 1 box", "an ok pill says nothing more")
        XCTAssertEqual(row(app, "Doliprane 1000").label, "Doliprane 1000, 2 boxes, Expired", "the worst box wins")
        XCTAssertEqual(row(app, "Thermometer").label, "Thermometer, 1 box, Expiry unknown", "never OK without a date")
        XCTAssertEqual(row(app, "Gaviscon").label, "Gaviscon, 1 box, Expiring soon")
        let names = app.buttons.matching(identifier: "cabinet.pill").allElementsBoundByIndex
            .sorted { ($0.frame.minY, $0.frame.minX) < ($1.frame.minY, $1.frame.minX) }.map(\.label)
        XCTAssertEqual(names.prefix(3).map { String($0.prefix(5)) }, ["Gavis", "Smect", "Spasf"])

        let dateOrOK = NSPredicate(format: "label CONTAINS 'Expires' OR label == 'OK' OR label CONTAINS '2027'")
        XCTAssertEqual(app.staticTexts.matching(dateOrOK).count, 0, "no date and no OK in the list")
    }

    func testAPillIsAtLeast44PointsHighAndALongNameKeepsItsCount() {
        let app = launch()
        let pill = row(app, "Smecta")
        XCTAssertTrue(pill.waitForExistence(timeout: 10))
        XCTAssertGreaterThanOrEqual(pill.frame.height, 44)
        XCTAssertGreaterThanOrEqual(pill.frame.width, 44)
    }

    func testSearchNarrowsTheListAndAnUnmatchedSearchOffersClear() {
        let app = launch()
        let search = app.textFields["cabinet.search"]
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
        XCTAssertEqual(app.staticTexts["cabinet.count"].label, "6 boxes")
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
        XCTAssertTrue(app.textFields["cabinet.search"].exists)
        // A Menu has a nested button over the same frame, so `isHittable` of the outer element is false.
        let filter = app.buttons["cabinet.filter.button"]
        XCTAssertTrue(filter.exists)
        XCTAssertGreaterThanOrEqual(filter.frame.height, 44)
        XCTAssertTrue(app.frame.contains(filter.frame), "the filter button stays on screen")
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
