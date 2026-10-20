import XCTest

/// The monthly reminder's opt-in (English): the card on the Expiring tab and the Settings switch, against a notification
/// center in memory (`-UITestNotifications`), so no system prompt appears and nothing is posted.
@MainActor
final class ReminderUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @discardableResult
    private func launch(notifications: String = "notDetermined", openExpiring: Bool = true, extra: [String] = [])
        -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-UITestingTemporaryStorage", "-UITestNotifications", notifications,
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US"
        ] + extra
        app.launch()
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15))
        if openExpiring { bar.buttons["Expiring"].tap() }
        return app
    }

    private func toggle(_ app: XCUIApplication) -> XCUIElement {
        app.switches["reminder.settings.toggle"]
    }

    private func openSettings(_ app: XCUIApplication) {
        app.tabBars.buttons["Settings"].tap()
        app.scroll(to: toggle(app))
        XCTAssertTrue(toggle(app).waitForExistence(timeout: 5))
    }

    // MARK: Card (N2)

    func testTheCardOffersTheReminderAndNothingWasAskedYet() {
        let app = launch()
        XCTAssertTrue(app.buttons["reminder.card.turnOn"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Monthly reminder"].exists)
        XCTAssertTrue(app.buttons["reminder.card.notNow"].exists)
        XCTAssertTrue(app.buttons["reminder.card.turnOn"].isHittable)
    }

    func testTurningOnFromTheCardHidesItAndSwitchesTheSettingOn() {
        let app = launch()
        app.buttons["reminder.card.turnOn"].tap()
        XCTAssertTrue(app.staticTexts["All clear"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["reminder.card.turnOn"].exists, "the card goes once reminders are on")
        openSettings(app)
        XCTAssertEqual(toggle(app).value as? String, "1")
    }

    func testNotNowHidesTheCardAndTheSettingStaysOff() {
        let app = launch()
        app.buttons["reminder.card.notNow"].tap()
        XCTAssertFalse(app.buttons["reminder.card.turnOn"].exists)
        // Away and back: still hidden.
        app.tabBars.buttons["Cabinet"].tap()
        app.tabBars.buttons["Expiring"].tap()
        XCTAssertTrue(app.staticTexts["All clear"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["reminder.card.turnOn"].exists)
        openSettings(app)
        XCTAssertEqual(toggle(app).value as? String, "0", "Settings always offers the switch")
    }

    func testRefusingThePromptKeepsTheCardWithAnExplanationAndOpenSettings() {
        let app = launch(notifications: "refuseOnPrompt")
        app.buttons["reminder.card.turnOn"].tap()
        XCTAssertTrue(app.buttons["reminder.card.openSettings"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Without notification permission, the reminder can’t be sent."].exists)
        XCTAssertFalse(app.buttons["reminder.card.turnOn"].exists)
        openSettings(app)
        XCTAssertEqual(toggle(app).value as? String, "0", "reminders stay off")
        XCTAssertTrue(app.buttons["reminder.settings.openSettings"].exists)
    }

    func testTheCardOffersOpenSettingsWhenTheSystemAlreadyRefuses() {
        let app = launch(notifications: "denied")
        XCTAssertTrue(app.buttons["reminder.card.openSettings"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["reminder.card.turnOn"].exists)
    }

    // MARK: Settings switch (N2, N3, N13)

    func testTheSettingsSwitchTurnsTheReminderOnAndOff() {
        let app = launch(openExpiring: false)
        openSettings(app)
        XCTAssertEqual(toggle(app).value as? String, "0", "off by default")
        XCTAssertTrue(app.staticTexts[
            "One notification on the 1st of the month at 9:00, with only the number of boxes, never a medicine name."
        ].exists)

        toggle(app).switches.firstMatch.tap()
        XCTAssertEqual(toggle(app).value as? String, "1")
        toggle(app).switches.firstMatch.tap()
        XCTAssertEqual(toggle(app).value as? String, "0")
    }

    func testTurningOnInSettingsWhenTheSystemRefusesExplainsAndOffersOpenSettings() {
        let app = launch(notifications: "denied", openExpiring: false)
        openSettings(app)
        XCTAssertTrue(
            app.staticTexts["Without notification permission, the reminder can’t be sent."].exists,
            "the refusal is explained before the user even tries")
        toggle(app).switches.firstMatch.tap()
        XCTAssertEqual(toggle(app).value as? String, "0", "it cannot be on")
        XCTAssertTrue(app.buttons["reminder.settings.openSettings"].exists)
        XCTAssertTrue(app.buttons["reminder.settings.openSettings"].isHittable)
    }

    // MARK: Accessibility (X4)

    func testTheCardAndTheSwitchStayUsableAtTheLargestTextSize() {
        let app = launch(extra: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        XCTAssertTrue(app.buttons["reminder.card.turnOn"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["reminder.card.turnOn"].isHittable)
        app.buttons["reminder.card.notNow"].tap()
        XCTAssertFalse(app.buttons["reminder.card.turnOn"].exists)
        openSettings(app)
        XCTAssertTrue(toggle(app).isHittable)
    }
}
