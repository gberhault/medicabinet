import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

@Suite struct CabinetTextTests {
    private func languageBundle(_ code: String) throws -> Bundle {
        let path = try #require(Bundle.main.path(forResource: code, ofType: "lproj"), "no \(code).lproj in the app")
        return try #require(Bundle(path: path))
    }

    @Test func aThirteenDigitCodeIsShownWithoutTheLeadingZeroOfItsGTIN() {
        #expect(CabinetText.code(gtin: "03400930000014") == "3400930000014")
    }

    @Test func aGTINThatDoesNotStartWithZeroIsShownAsIs() {
        #expect(CabinetText.code(gtin: "13400930000011") == "13400930000011")
    }

    @Test(arguments: [("fr", "Pas de code"), ("en", "No code")])
    func aBoxWithoutACodeSaysSo(code: String, expected: String) throws {
        let bundle = try languageBundle(code)
        #expect(CabinetText.code(gtin: nil, bundle: bundle) == expected)
        #expect(CabinetText.code(gtin: "", bundle: bundle) == expected)
    }

    @Test(arguments: [("fr", 0, "0 boîte"), ("fr", 1, "1 boîte"), ("fr", 3, "3 boîtes"), ("en", 1, "1 box"), ("en", 3, "3 boxes")])
    func countsArePluralAwareInEachLanguage(code: String, count: Int, expected: String) throws {
        let bundle = try languageBundle(code)
        let locale = Locale(identifier: code)
        #expect(CabinetText.boxCount(count, bundle: bundle, locale: locale) == expected)
        #expect(CabinetText.rowBoxes(count, bundle: bundle, locale: locale) == expected)
    }

    @Test(arguments: [("fr", "Doliprane : utilisée"), ("en", "Doliprane: used up")])
    func undoMessagesNameTheProduct(code: String, expected: String) throws {
        let bundle = try languageBundle(code)
        #expect(CabinetText.undoMessage("undo.usedUp", name: "Doliprane", bundle: bundle) == expected)
    }

    @Test func everyExpiryStatusHasATitleSymbolAndKey() {
        for status in [ExpiryStatus.expired, .expiringSoon, .ok, .unknown] {
            #expect(!ExpiryStatusStyle.symbol(status).isEmpty)
            #expect(ExpiryStatusStyle.key(status) == "expiry.status.\(status.rawValue)")
        }
    }
}
