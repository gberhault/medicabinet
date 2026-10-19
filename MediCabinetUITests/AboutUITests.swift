import XCTest

@MainActor
final class AboutUITests: XCTestCase {
    func testAboutShowsVersionBuildAndTheNoMedicalAdviceNotice() {
        continueAfterFailure = false
        let app = UITestApp.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 15))
        app.tabBars.buttons["Settings"].tap()
        app.buttons["settings.about"].tap()

        let version = app.staticTexts["about.version"]
        XCTAssertTrue(version.waitForExistence(timeout: 5))
        XCTAssertNotNil(version.label.wholeMatch(of: /Version \d+\.\d+\.\d+ \(\d+\)/), "version line was \(version.label)")

        let disclaimer = app.staticTexts["about.disclaimer"]
        XCTAssertTrue(disclaimer.exists)
        XCTAssertTrue(disclaimer.label.contains("no medical advice"))
    }
}
