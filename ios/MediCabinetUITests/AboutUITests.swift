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

    func testAboutCitesTheReferenceDataSourceItsDateAndThatItIsNotEndorsed() {
        continueAfterFailure = false
        let app = UITestApp.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 15))
        app.tabBars.buttons["Settings"].tap()
        app.buttons["settings.about"].tap()

        // The bundled database is opened for About (nothing opens it at launch): its date replaces the loading line.
        let date = app.staticTexts["about.reference.date"]
        XCTAssertTrue(date.waitForExistence(timeout: 10), "the reference data date never appeared")
        XCTAssertTrue(date.label.hasPrefix("Data as of "), "date line was \(date.label)")
        XCTAssertFalse(app.staticTexts["about.reference.unavailable"].exists)

        XCTAssertTrue(app.staticTexts["Source: Base de Données Publique des Médicaments"].exists)
        XCTAssertTrue(app.links["about.reference.link"].exists || app.buttons["about.reference.link"].exists)
        let notEndorsed = app.staticTexts["about.reference.notEndorsed"]
        XCTAssertTrue(notEndorsed.exists)
        XCTAssertTrue(notEndorsed.label.contains("not affiliated with or endorsed by ANSM"))
    }
}
