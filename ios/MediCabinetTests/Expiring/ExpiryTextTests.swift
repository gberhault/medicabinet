import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

@MainActor
@Suite struct ExpiryTextTests {
    private func bundle(_ code: String) throws -> Bundle {
        let path = try #require(Bundle.main.path(forResource: code, ofType: "lproj"), "no \(code).lproj in the app")
        return try #require(Bundle(path: path))
    }

    private func expiry(_ year: Int, _ month: Int, _ day: Int?) throws -> ExpiryDate {
        try ExpiryDate.make(year: year, month: month, day: day)
    }

    // MARK: Dates (E4)

    @Test func aDayDateShowsTheDayTheMonthAndTheYear() throws {
        let date = try expiry(2027, 3, 15)
        #expect(ExpiryText.expiryLine(date, locale: Locale(identifier: "en_US"), bundle: try bundle("en"))
            == "Expires: March 15, 2027")
        #expect(ExpiryText.expiryLine(date, locale: Locale(identifier: "fr_FR"), bundle: try bundle("fr"))
            == "Péremption : 15 mars 2027")
    }

    @Test func aMonthPreciseDateShowsTheMonthAndYearOnlyNeverAnInventedDay() throws {
        let month = try expiry(2027, 3, nil)
        #expect(ExpiryText.expiryLine(month, locale: Locale(identifier: "en_US"), bundle: try bundle("en"))
            == "Expires: March 2027")
        #expect(ExpiryText.expiryLine(month, locale: Locale(identifier: "fr_FR"), bundle: try bundle("fr"))
            == "Péremption : mars 2027")
    }

    @Test func aLeapDayAndAYearEndFormatInTheirOwnMonth() throws {
        let locale = Locale(identifier: "en_US")
        let english = try bundle("en")
        #expect(ExpiryText.expiryLine(try expiry(2028, 2, 29), locale: locale, bundle: english) == "Expires: February 29, 2028")
        #expect(ExpiryText.expiryLine(try expiry(2027, 12, nil), locale: locale, bundle: english) == "Expires: December 2027")
    }

    @Test func anUnknownExpiryHasNoDateLine() {
        #expect(ExpiryText.expiryLine(nil) == nil)
    }

    // MARK: Window, empty state, badge

    @Test(arguments: [("fr", "60 jours"), ("en", "60 days")])
    func theWindowOptionReadsAsDays(code: String, expected: String) throws {
        #expect(ExpiryText.windowOption(.days60, bundle: try bundle(code)) == expected)
    }

    @Test(arguments: [
        ("fr", ExpiryWindow.days30, "Rien ne périme dans les 30 prochains jours."),
        ("en", ExpiryWindow.days90, "Nothing expires in the next 90 days.")
    ])
    func theEmptyStateNamesTheWindow(code: String, window: ExpiryWindow, expected: String) throws {
        #expect(ExpiryText.emptyMessage(window: window, bundle: try bundle(code)) == expected)
    }

    @Test(arguments: [
        ("fr", 1, "1 boîte à vérifier"), ("fr", 3, "3 boîtes à vérifier"),
        ("en", 1, "1 box needs attention"), ("en", 3, "3 boxes need attention")
    ])
    func theBadgeIsReadWithTheRightPlural(code: String, count: Int, expected: String) throws {
        #expect(ExpiryText.badgeAccessibility(count, bundle: try bundle(code), locale: Locale(identifier: code)) == expected)
    }

    // MARK: Row for VoiceOver

    @Test func aRowIsReadAsNameStatusDateAndLocation() throws {
        let text = ExpiryText.rowAccessibility(
            name: "Doliprane", status: "Expired", expiryLine: "Expires: March 2027", location: "Home",
            bundle: try bundle("en"))
        #expect(text == "Doliprane, Expired, Expires: March 2027, Home")
    }

    @Test func anUnknownExpiryIsNotReadTwice() throws {
        let text = ExpiryText.rowAccessibility(
            name: "Thermometer", status: "Expiry unknown", expiryLine: nil, location: "Home", bundle: try bundle("en"))
        #expect(text == "Thermometer, Expiry unknown, Home")
    }

    @Test func theExpiresTodayWordingExistsInBothLanguages() throws {
        #expect(try bundle("en").localizedString(forKey: ExpiryStatusStyle.expiresTodayKey, value: nil, table: nil) == "Expires today")
        #expect(try bundle("fr").localizedString(forKey: ExpiryStatusStyle.expiresTodayKey, value: nil, table: nil) == "Périme aujourd’hui")
    }
}
