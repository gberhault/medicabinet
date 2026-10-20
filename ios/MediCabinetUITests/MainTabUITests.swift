import XCTest

/// Shared launch configuration: English, a throwaway database, optional accessibility settings.
@MainActor
enum UITestApp {
    static func launch(extraArguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-UITestingTemporaryStorage", "-UITestNotifications", "notDetermined",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US"
        ] + extraArguments
        app.launch()
        return app
    }
}

extension XCUIApplication {
    /// Swipes up, at most `attempts` times, until `element` exists and can be tapped: the Settings list is longer than
    /// the screen at large text sizes.
    @MainActor
    func scroll(to element: XCUIElement, attempts: Int = 8) {
        for _ in 0..<attempts where !(element.exists && element.isHittable) { swipeUp() }
    }
}

@MainActor
final class MainTabUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testFourTabsAreShown() {
        let app = UITestApp.launch()
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15))
        for title in ["Scan", "Cabinet", "Expiring", "Settings"] {
            XCTAssertTrue(bar.buttons[title].exists, "missing tab \(title)")
        }
    }

    // Issue #53: the app opens on the Cabinet, not on the camera.
    func testTheAppOpensOnTheCabinetTabWithoutStartingTheCamera() {
        let app = UITestApp.launch()
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15))
        XCTAssertTrue(bar.buttons["Cabinet"].isSelected)
        XCTAssertTrue(app.staticTexts["The cabinet is empty"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["scan.permission.allow"].exists)
        XCTAssertFalse(app.buttons["scan.count"].exists)
    }

    func testEachTabWithNothingToShowExplainsItself() {
        let app = UITestApp.launch()
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15))
        let expectations = [
            ("Cabinet", "The cabinet is empty"),
            ("Expiring", "All clear")
        ]
        for (tab, title) in expectations {
            bar.buttons[tab].tap()
            XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 5), "no empty state for \(tab)")
        }
    }

    func testTabsRemainUsableAtAccessibilityTextSizeInDarkMode() {
        let app = UITestApp.launch(extraArguments: [
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
            "-AppleInterfaceStyle", "Dark"
        ])
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15))
        for button in bar.buttons.allElementsBoundByIndex {
            XCTAssertTrue(button.isHittable, "\(button.label) is not hittable at the largest text size")
        }
        XCTAssertEqual(bar.buttons.count, 4)
    }
}
