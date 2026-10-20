import XCTest

/// Settings → "Expiring soon": the window, and that the Cabinet tab follows it at once (W1, W2, W5).
@MainActor
final class ExpirySettingsUITests: XCTestCase {
    /// 45 days left: expiring soon with the default 60-day window, OK once the window is 30 days.
    private let seed = """
    [{"name":"Nurofen","gtin":"03400930000014","category":"pain-fever","expiryInDays":45}]
    """

    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-UITestingTemporaryStorage", "-UITestNotifications", "notDetermined",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US"
        ] + extra
        app.launchEnvironment["MEDICABINET_UI_TEST_CABINET"] = seed
        app.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 15))
        return app
    }

    func testTheWindowDefaultsToSixtyDaysAndTheFooterExplainsIt() {
        let app = launch()
        app.tabBars.buttons["Settings"].tap()
        let picker = app.buttons["settings.expiryWindow"]
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        XCTAssertTrue(picker.label.contains("60 days") || picker.value as? String == "60 days", "was \(picker.label)")
        XCTAssertTrue(app.staticTexts["Boxes expiring within this many days are shown as expiring soon."].exists)
    }

    func testChangingTheWindowChangesTheStatusInTheCabinetTab() {
        let app = launch()
        app.tabBars.buttons["Cabinet"].tap()
        // The Cabinet pills say a status only when there is something to report, in their spoken label.
        func pill(_ label: String) -> XCUIElement {
            app.buttons.matching(identifier: "cabinet.pill").matching(NSPredicate(format: "label == %@", label)).firstMatch
        }
        XCTAssertTrue(pill("Nurofen, 1 box, Expiring soon").waitForExistence(timeout: 10))

        app.tabBars.buttons["Settings"].tap()
        app.buttons["settings.expiryWindow"].tap()
        app.buttons["30 days"].tap()

        app.tabBars.buttons["Cabinet"].tap()
        XCTAssertTrue(pill("Nurofen, 1 box").waitForExistence(timeout: 10), "45 days left is outside a 30-day window")
        XCTAssertFalse(pill("Nurofen, 1 box, Expiring soon").exists)
    }

    func testTheWindowPickerStaysUsableAtTheLargestTextSize() {
        let app = launch(extra: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(app.buttons["settings.expiryWindow"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["settings.expiryWindow"].isHittable)
    }
}
