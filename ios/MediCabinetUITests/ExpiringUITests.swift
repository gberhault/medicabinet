import XCTest

/// The Expiring tab on a seeded cabinet (English): sections, status words, dates, badge, Thrown away with Undo.
@MainActor
final class ExpiringUITests: XCTestCase {
    /// Relative to today, so the seed never rots: two expired boxes (one month-precise, the oldest first), one that
    /// expires today, one soon, one fine (never listed) and one with no date.
    private let seed = """
    [{"name":"Old cream","gtin":"03400930000021","category":"skin","expiryInDays":-10,"location":"Car"},
     {"name":"Monthly syrup","gtin":"03400930000038","category":"cold-ent","expiryInDays":-45,"monthPrecision":true},
     {"name":"Today pills","gtin":"03400930000045","category":"pain-fever","expiryInDays":0},
     {"name":"Soon drops","gtin":"03400930000052","category":"eyes","expiryInDays":20},
     {"name":"Fine tablets","gtin":"03400930000069","category":"digestion","expiryInDays":400},
     {"name":"Thermometer","category":"first-aid"}]
    """

    override func setUp() {
        continueAfterFailure = false
    }

    @discardableResult
    private func launch(seeded: Bool = true, openExpiring: Bool = true, extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-UITestingTemporaryStorage", "-UITestNotifications", "notDetermined",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US"
        ] + extra
        if seeded { app.launchEnvironment["MEDICABINET_UI_TEST_CABINET"] = seed }
        app.launch()
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15))
        if openExpiring {
            bar.buttons["Expiring"].tap()
            // The reminder offer sits above the list: these tests are about the list (ReminderUITests cover the card).
            let notNow = app.buttons["reminder.card.notNow"]
            if notNow.waitForExistence(timeout: 10) { notNow.tap() }
        }
        return app
    }

    /// A row by the start of its spoken label (the name). A row is one accessibility element: name, status, date and
    /// location in its label.
    private func row(_ app: XCUIApplication, _ name: String) -> XCUIElement {
        app.staticTexts.matching(identifier: "expiring.row")
            .matching(NSPredicate(format: "label BEGINSWITH %@", name + ",")).firstMatch
    }

    private func rowNames(_ app: XCUIApplication) -> [String] {
        let rows = app.staticTexts.matching(identifier: "expiring.row")
        return (0..<rows.count).map { rows.element(boundBy: $0).label.components(separatedBy: ",").first ?? "" }
    }

    // MARK: Sections

    func testBoxesAreListedInThreeSectionsOldestExpiryFirstAndOKBoxesAreHidden() {
        let app = launch()
        XCTAssertTrue(row(app, "Old cream").waitForExistence(timeout: 10))
        for section in ["expired", "expiringSoon", "unknown"] {
            XCTAssertTrue(app.staticTexts["expiring.section.\(section)"].exists, "missing section \(section)")
        }
        XCTAssertEqual(rowNames(app), ["Monthly syrup", "Old cream", "Today pills", "Soon drops", "Thermometer"])
        XCTAssertFalse(row(app, "Fine tablets").exists, "OK boxes are not listed")
    }

    func testEachRowSaysItsStatusInWordsItsLocationAndItsDate() {
        let app = launch()
        let cream = row(app, "Old cream")
        XCTAssertTrue(cream.waitForExistence(timeout: 10))
        XCTAssertTrue(cream.label.contains("Expired"), "status as words: \(cream.label)")
        XCTAssertTrue(cream.label.contains("Car"), "location: \(cream.label)")
        XCTAssertTrue(cream.label.contains("Expires: "), "date: \(cream.label)")
        XCTAssertTrue(row(app, "Today pills").label.contains("Expires today"))
        XCTAssertTrue(row(app, "Soon drops").label.contains("Expiring soon"))
        let thermometer = row(app, "Thermometer")
        XCTAssertTrue(thermometer.label.contains("Expiry unknown"), "an unknown expiry is said, never OK")
        XCTAssertFalse(thermometer.label.contains("OK"))
    }

    func testAMonthPreciseDateShowsTheMonthAndYearButNoDay() {
        let app = launch()
        let syrup = row(app, "Monthly syrup")
        XCTAssertTrue(syrup.waitForExistence(timeout: 10))
        let day = Calendar(identifier: .gregorian).date(byAdding: .day, value: -45, to: Date()) ?? Date()
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US")
        formatter.dateFormat = "MMMM yyyy"
        XCTAssertTrue(
            syrup.label.contains("Expires: \(formatter.string(from: day))"), "month and year, got: \(syrup.label)")
        XCTAssertNil(syrup.label.firstMatch(of: /\d{1,2}, \d{4}/), "no invented day: \(syrup.label)")
    }

    func testNothingToReportShowsAnEmptyStateNamingTheWindow() {
        let app = launch(seeded: false)
        XCTAssertTrue(app.staticTexts["All clear"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Nothing expires in the next 60 days."].exists)
    }

    func testTheEmptyStateNamesTheWindowChosenInSettings() {
        let app = launch(seeded: false, openExpiring: false)
        app.tabBars.buttons["Settings"].tap()
        app.buttons["settings.expiryWindow"].tap()
        app.buttons["90 days"].tap()
        app.tabBars.buttons["Expiring"].tap()
        XCTAssertTrue(app.staticTexts["Nothing expires in the next 90 days."].waitForExistence(timeout: 10))
    }

    // MARK: Badge

    func testTheTabBadgeCountsExpiredAndExpiringSoonBoxesWhicheverTabIsSelected() {
        let app = launch(openExpiring: false)  // the Cabinet tab is selected
        let tab = app.tabBars.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Expiring'")).firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        let predicate = NSPredicate(format: "label CONTAINS '4' OR value CONTAINS '4'")
        let counted = expectation(for: predicate, evaluatedWith: tab)
        wait(for: [counted], timeout: 10)
        XCTAssertFalse(
            tab.label.contains("5") || (tab.value as? String ?? "").contains("5"), "the unknown box is not counted")
    }

    func testNoBadgeWhenNothingNeedsAttention() {
        let app = launch(seeded: false, openExpiring: false)
        let tab = app.tabBars.buttons["Expiring"]
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        XCTAssertEqual(tab.label, "Expiring")
        XCTAssertFalse((tab.value as? String ?? "").contains("need"))
    }

    // MARK: Thrown away

    func testSwipingThrowsABoxAwayAndUndoBringsItBack() {
        let app = launch()
        let cream = row(app, "Old cream")
        XCTAssertTrue(cream.waitForExistence(timeout: 10))
        cream.swipeLeft()
        app.buttons["expiring.thrownAway"].tap()

        let message = app.staticTexts["undo.message"]
        XCTAssertTrue(message.waitForExistence(timeout: 5))
        XCTAssertEqual(message.label, "Old cream thrown away")
        XCTAssertFalse(row(app, "Old cream").exists)

        app.buttons["undo.action"].tap()
        XCTAssertTrue(row(app, "Old cream").waitForExistence(timeout: 5))
    }

    func testRowsOpenNothing() {
        let app = launch()
        let cream = row(app, "Old cream")
        XCTAssertTrue(cream.waitForExistence(timeout: 10))
        cream.tap()
        XCTAssertFalse(app.staticTexts["box.name"].exists)
        XCTAssertTrue(app.navigationBars["Expiring"].exists)
        XCTAssertTrue(row(app, "Old cream").exists)
    }

    // MARK: Accessibility

    func testTheListStaysUsableAtTheLargestTextSizeInDarkMode() {
        let app = launch(extra: [
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
            "-AppleInterfaceStyle", "Dark"
        ])
        // The first row: at this size the later ones are below the fold.
        let syrup = row(app, "Monthly syrup")
        XCTAssertTrue(syrup.waitForExistence(timeout: 10))
        XCTAssertTrue(syrup.label.contains("Expired"))
        XCTAssertTrue(syrup.isHittable)
        syrup.swipeLeft()
        let throwAway = app.buttons["expiring.thrownAway"]
        XCTAssertTrue(throwAway.waitForExistence(timeout: 5))
        XCTAssertTrue(throwAway.isHittable)
    }
}
