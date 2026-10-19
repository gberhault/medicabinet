import XCTest

@MainActor
final class LaunchUITests: XCTestCase {
    func testAppLaunches() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["MediCabinet"].waitForExistence(timeout: 10))
    }
}
