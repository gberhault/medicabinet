import XCTest

/// A big cabinet: 1 000 boxes of 250 products in one category must render as pills and scroll (a smoke check with a
/// generous budget, to catch a layout that measures every pill at once or hangs).
@MainActor
final class CabinetScaleUITests: XCTestCase {
    private static let products = 250
    private static let boxesPerProduct = 4

    override func setUp() {
        continueAfterFailure = false
    }

    private func thousandBoxesSeed() -> String {
        var items: [String] = []
        for product in 0..<Self.products {
            let gtin = "0340093" + String(format: "%07d", product)
            let item = "{\"name\":\"Medicine \(product)\",\"gtin\":\"\(gtin)\",\"category\":\"pain-fever\",\"expiryInDays\":400}"
            items.append(contentsOf: Array(repeating: item, count: Self.boxesPerProduct))
        }
        return "[" + items.joined(separator: ",") + "]"
    }

    func testAThousandBoxesInOneCategoryRenderAndScroll() {
        let app = CabinetUI.launch(seed: thousandBoxesSeed())
        let count = app.staticTexts["cabinet.count"]
        XCTAssertTrue(count.waitForExistence(timeout: 60))
        XCTAssertEqual(count.label.replacingOccurrences(of: ",", with: ""), "1000 boxes", "the count may be grouped: 1,000")
        let first = CabinetUI.row(app, "Medicine 0, 4 boxes")
        XCTAssertTrue(first.waitForExistence(timeout: 30))

        let started = Date()
        for _ in 0..<6 { app.swipeUp(velocity: .fast) }
        for _ in 0..<6 { app.swipeDown(velocity: .fast) }
        XCTAssertLessThan(Date().timeIntervalSince(started), 60, "twelve fast swipes over 250 pills")
        XCTAssertTrue(first.waitForExistence(timeout: 10), "back at the top")
    }
}
