import XCTest

/// Shared launch configuration: English, a throwaway database, optional accessibility settings.
@MainActor
enum UITestApp {
    static func launch(extraArguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestingTemporaryStorage", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extraArguments
        app.launch()
        return app
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

    func testEachTabWithNothingToShowExplainsItself() {
        let app = UITestApp.launch()
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15))
        let expectations = [
            ("Cabinet", "The cabinet is empty"),
            ("Expiring", "Expiry tracking is coming soon")
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
