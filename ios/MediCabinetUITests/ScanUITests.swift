import XCTest

/// The Scan tab end to end, with a simulated camera (`MEDICABINET_UI_TEST_SCANS`) and no real permission prompt.
@MainActor
final class ScanUITests: XCTestCase {
    private let separator = "<GS>"
    private let doliprane = "03400930000014"
    private let spasfon = "03400930000021"

    override func setUp() {
        continueAfterFailure = false
    }

    private func payload(_ gtin: String, expiry: String? = "270331", serial: String? = nil) -> String {
        var text = "01\(gtin)"
        if let expiry { text += "17\(expiry)" }
        if let serial { text += "10LOT1\(separator)21\(serial)" }
        return text
    }

    private func launch(
        access: String, scans: [String] = [], products: String? = nil, extra: [String] = [], interval: Int = 1200
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestingTemporaryStorage", "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
                               "-UITestCameraAccess", access] + extra
        app.launchEnvironment["MEDICABINET_UI_TEST_SCANS"] = scans.joined(separator: "|")
        app.launchEnvironment["MEDICABINET_UI_TEST_SCAN_INTERVAL_MS"] = String(interval)
        if let products { app.launchEnvironment["MEDICABINET_UI_TEST_PRODUCTS"] = products }
        app.launch()
        return app
    }

    /// The centre of a Form toggle row is its label; the switch itself is at the trailing edge.
    private func flip(_ toggle: XCUIElement) {
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    }

    /// SwiftUI updates `isEnabled` a beat after a tap.
    private func waitUntilEnabled(_ element: XCUIElement, _ enabled: Bool = true, timeout: TimeInterval = 5) {
        let predicate = NSPredicate(format: "isEnabled == %@", NSNumber(value: enabled))
        expectation(for: predicate, evaluatedWith: element)
        waitForExpectations(timeout: timeout)
    }

    private func count(_ app: XCUIApplication) -> String? {
        app.buttons["scan.count"].value as? String
    }

    // MARK: permission and fallbacks

    func testTheCameraIsExplainedBeforeTheSystemPromptAndThenStarts() {
        let app = launch(access: "notDetermined")
        let allow = app.buttons["scan.permission.allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Allow the camera"].exists)
        XCTAssertTrue(app.buttons["scan.manual.open"].exists, "manual entry is offered before the camera is allowed")
        allow.tap()
        XCTAssertTrue(app.buttons["scan.count"].waitForExistence(timeout: 10))
        XCTAssertEqual(count(app), "Boxes added: 0")
    }

    func testADeniedCameraOffersSettingsAndManualEntry() {
        let app = launch(access: "denied")
        XCTAssertTrue(app.staticTexts["Camera turned off"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["scan.permission.settings"].exists)
        XCTAssertTrue(app.buttons["scan.manual.open"].exists)
    }

    func testADeviceThatCannotScanStillAllowsManualEntry() {
        let app = launch(access: "unsupported")
        XCTAssertTrue(app.staticTexts["Scanning unavailable"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["scan.manual.open"].exists)
    }

    func testABoxCanBeTypedInWithoutTheCamera() {
        let app = launch(access: "denied")
        app.buttons["scan.manual.open"].tap()
        let name = app.textFields["manual.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText("Thermometer")
        XCTAssertFalse(app.buttons["manual.add"].isEnabled, "no date chosen yet: cannot be saved")
        flip(app.switches["expiry.notPrinted"])
        waitUntilEnabled(app.buttons["manual.add"])
        app.buttons["manual.add"].tap()
        XCTAssertTrue(app.buttons["scan.manual.open"].waitForExistence(timeout: 10), "back on the Scan tab")
    }

    func testAWrongTypedCodeIsRefused() {
        let app = launch(access: "denied")
        app.buttons["scan.manual.open"].tap()
        let code = app.textFields["manual.code"]
        XCTAssertTrue(code.waitForExistence(timeout: 10))
        code.tap()
        code.typeText("12345")
        app.textFields["manual.name"].tap()
        app.textFields["manual.name"].typeText("X")
        flip(app.switches["expiry.notPrinted"])
        app.buttons["manual.add"].tap()
        XCTAssertTrue(app.staticTexts["Invalid code"].waitForExistence(timeout: 5))
    }

    // MARK: bulk scan

    func testScanningBoxesOneAfterTheOtherAddsThemAndRefusesADuplicate() {
        let app = launch(
            access: "authorized",
            scans: [payload(doliprane, serial: "S1"), payload(spasfon, serial: "S2"), payload(doliprane, serial: "S1")],
            products: "\(doliprane)=DOLIPRANE|\(spasfon)=SPASFON"
        )
        XCTAssertTrue(app.buttons["scan.count"].waitForExistence(timeout: 15))
        let added = NSPredicate(format: "value == %@", "Boxes added: 2")
        expectation(for: added, evaluatedWith: app.buttons["scan.count"])
        waitForExpectations(timeout: 15)
        XCTAssertTrue(app.staticTexts["Already in the cabinet: Medicine cabinet"].waitForExistence(timeout: 15))
        XCTAssertEqual(count(app), "Boxes added: 2", "the duplicate was not counted")
    }

    func testTheSessionListShowsWhatWasAddedAndALostBoxCanBeUndeleted() {
        let app = launch(
            access: "authorized", scans: [payload(doliprane, serial: "S1")], products: "\(doliprane)=DOLIPRANE"
        )
        expectation(for: NSPredicate(format: "value == %@", "Boxes added: 1"), evaluatedWith: app.buttons["scan.count"])
        waitForExpectations(timeout: 20)
        app.buttons["scan.count"].tap()

        let row = app.buttons.containing(.staticText, identifier: "DOLIPRANE").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.swipeLeft()
        app.buttons["Delete"].tap()
        XCTAssertTrue(app.staticTexts["Box deleted"].waitForExistence(timeout: 5))
        app.buttons["session.undoDelete"].tap()
        XCTAssertTrue(app.buttons.containing(.staticText, identifier: "DOLIPRANE").firstMatch.waitForExistence(timeout: 5))
    }

    func testAMissingDateIsAskedWhileTheBoxIsInHandAndDateNotPrintedIsSavedAsUnknown() {
        let app = launch(
            access: "authorized", scans: [payload(doliprane, expiry: nil, serial: "S1")], products: "\(doliprane)=DOLIPRANE"
        )
        let add = app.buttons["completion.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 20))
        XCTAssertFalse(add.isEnabled, "no date chosen: it must be asked, never defaulted")
        flip(app.switches["expiry.notPrinted"])
        waitUntilEnabled(add)
        add.tap()
        XCTAssertTrue(app.staticTexts["Expiry unknown"].waitForExistence(timeout: 10), "an unknown expiry is shown, never as OK")
        app.buttons["scan.done"].tap()
        XCTAssertTrue(app.staticTexts["Without an expiry date: 1"].waitForExistence(timeout: 5))
        app.buttons["summary.close"].tap()
        XCTAssertEqual(count(app), "Boxes added: 0")
    }

    func testAnUnknownProductAsksForANameAndCancelAddsNothing() {
        let app = launch(access: "authorized", scans: [payload(doliprane, serial: "S1")])
        XCTAssertTrue(app.textFields["completion.name"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["completion.add"].isEnabled)
        app.buttons["completion.cancel"].tap()
        XCTAssertEqual(count(app), "Boxes added: 0")
    }

    // MARK: remove mode

    private func selectMode(_ app: XCUIApplication, _ title: String) {
        let segment = app.segmentedControls.buttons[title]
        XCTAssertTrue(segment.waitForExistence(timeout: 10))
        segment.tap()
    }

    private func waitForCount(_ app: XCUIApplication, _ text: String, timeout: TimeInterval = 30) {
        expectation(for: NSPredicate(format: "value == %@", text), evaluatedWith: app.buttons["scan.count"])
        waitForExpectations(timeout: timeout)
    }

    func testTheScannerAlwaysOpensInAddModeAndRemoveSaysSoInTextAndIcon() {
        let app = launch(access: "authorized")
        XCTAssertTrue(app.segmentedControls.buttons["Add"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.segmentedControls.buttons["Add"].isSelected)
        XCTAssertFalse(app.staticTexts["scan.remove.title"].exists)
        selectMode(app, "Remove")
        XCTAssertTrue(app.staticTexts["Removing boxes"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["scan.removal.reason"].exists)
        XCTAssertEqual(app.buttons["scan.removal.reason"].value as? String, "Used up")
        XCTAssertEqual(count(app), "Boxes removed: 0")
        // Leaving the tab and coming back resets to Add.
        app.tabBars.buttons.element(boundBy: 3).tap()
        app.tabBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.segmentedControls.buttons["Add"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.segmentedControls.buttons["Add"].isSelected)
    }

    func testScanningAKnownBoxInRemoveModeTakesItOutAndUndoPutsItBack() {
        // First scan: added in Add mode. Second scan (same box, 9 s later): removed once the mode is Remove.
        let app = launch(
            access: "authorized", scans: [payload(doliprane, serial: "S1"), payload(doliprane, serial: "S1")],
            products: "\(doliprane)=DOLIPRANE", interval: 7000
        )
        waitForCount(app, "Boxes added: 1")
        selectMode(app, "Remove")
        XCTAssertTrue(app.staticTexts["Box removed: used up"].waitForExistence(timeout: 30))
        XCTAssertEqual(count(app), "Boxes removed: 1")
        app.buttons["scan.notice.undoRemoval"].tap()
        waitForCount(app, "Boxes removed: 0", timeout: 10)
    }

    func testABoxThatIsNotInTheCabinetIsReportedAndNeverAdded() {
        // The first scan is consumed in Add mode; the second (another product) arrives once the mode is Remove.
        let app = launch(
            access: "authorized", scans: [payload(doliprane, serial: "S1"), payload(spasfon, serial: "S9")],
            products: "\(doliprane)=DOLIPRANE|\(spasfon)=SPASFON", interval: 7000
        )
        waitForCount(app, "Boxes added: 1")
        selectMode(app, "Remove")
        XCTAssertTrue(app.staticTexts["Not in the cabinet"].waitForExistence(timeout: 30))
        XCTAssertEqual(count(app), "Boxes removed: 0")
        XCTAssertFalse(app.buttons["completion.add"].exists)
    }

    func testSeveralMatchingBoxesAskWhichOneToRemove() {
        let app = launch(
            access: "authorized",
            scans: [payload(doliprane, expiry: "270331"), payload(doliprane, expiry: "280331"), payload(doliprane, expiry: nil)],
            products: "\(doliprane)=DOLIPRANE", interval: 6000
        )
        waitForCount(app, "Boxes added: 1")
        let another = app.buttons["scan.another.add"]
        XCTAssertTrue(another.waitForExistence(timeout: 30))
        another.tap()
        waitForCount(app, "Boxes added: 2", timeout: 10)
        selectMode(app, "Remove")
        let remove = app.buttons["candidate.remove"]
        XCTAssertTrue(remove.waitForExistence(timeout: 30))
        XCTAssertEqual(app.buttons.matching(identifier: "candidate.row").count, 2)
        remove.tap()
        XCTAssertTrue(app.staticTexts["Box removed: used up"].waitForExistence(timeout: 10))
        XCTAssertEqual(count(app), "Boxes removed: 1")
    }

    func testDoneInRemoveModeSummarisesWhatWasTakenOut() {
        let app = launch(
            access: "authorized", scans: [payload(doliprane, serial: "S1"), payload(doliprane, serial: "S1")],
            products: "\(doliprane)=DOLIPRANE", interval: 7000
        )
        waitForCount(app, "Boxes added: 1")
        selectMode(app, "Remove")
        XCTAssertTrue(app.staticTexts["Box removed: used up"].waitForExistence(timeout: 30))
        app.buttons["scan.done"].tap()
        XCTAssertTrue(app.staticTexts["summary.usedUp"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["summary.usedUp"].label, "1 box used up")
        XCTAssertEqual(app.staticTexts["summary.thrownAway"].label, "0 boxes thrown away")
    }

    func testTheScannerRemainsUsableAtTheLargestTextSizeInDarkMode() {
        let app = launch(
            access: "authorized", extra: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
                                          "-AppleInterfaceStyle", "Dark"]
        )
        XCTAssertTrue(app.buttons["scan.count"].waitForExistence(timeout: 15))
        for id in ["scan.count", "scan.done", "scan.sound", "scan.manual.open"] {
            XCTAssertTrue(app.buttons[id].isHittable, "\(id) is not hittable at the largest text size")
        }
    }
}
