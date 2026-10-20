import XCTest

/// What the Cabinet UI tests share: a seeded cabinet (English) and the lookups of its screens.
enum CabinetUI {
    /// Statuses are relative to today, so the seed never rots: Doliprane x2 (one expired, one ok), Spasfon (ok),
    /// a thermometer with no expiry, Gaviscon in the car (expiring soon), Smecta with a pharmaceutical form in its name.
    static let seed = """
    [{"name":"Doliprane 1000","gtin":"03400930000014","category":"pain-fever","expiryInDays":-5,"serial":"A1"},
     {"name":"Doliprane 1000","gtin":"03400930000014","category":"pain-fever","expiryInDays":500,"serial":"A2","opened":true,"lot":"LOT9"},
     {"name":"Spasfon","gtin":"03400930000021","category":"digestion","expiryInDays":400},
     {"name":"Thermometer","category":"first-aid"},
     {"name":"Gaviscon","gtin":"03400930000038","category":"digestion","expiryInDays":20,"location":"Car"},
     {"name":"Smecta 3 g, poudre pour suspension buvable","gtin":"03400930000045","category":"digestion","expiryInDays":300}]
    """

    @MainActor
    static func launch(seeded: Bool = true, extra: [String] = [], seed customSeed: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestingTemporaryStorage", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extra
        if seeded { app.launchEnvironment["MEDICABINET_UI_TEST_CABINET"] = customSeed ?? seed }
        app.launch()
        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15))
        bar.buttons["Cabinet"].tap()
        return app
    }

    /// A static text by its visible text (identifiers set on a container would hide it from `staticTexts["…"]`).
    @MainActor
    static func text(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label ==[c] %@ OR label ENDSWITH[c] %@", label, ", " + label)).firstMatch
    }

    /// The pill of a product, by the start of its spoken label (the full name, then the count and status).
    @MainActor
    static func row(_ app: XCUIApplication, _ name: String) -> XCUIElement {
        app.buttons.matching(identifier: "cabinet.pill")
            .matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
    }
}
