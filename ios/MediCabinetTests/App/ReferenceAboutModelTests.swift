import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@MainActor
@Suite struct ReferenceAboutModelTests {
    private func languageBundle(_ code: String) throws -> Bundle {
        let path = try #require(Bundle.main.path(forResource: code, ofType: "lproj"), "no \(code).lproj in the app")
        return try #require(Bundle(path: path))
    }

    private func date(_ iso: String) throws -> ReferenceDataDate {
        try #require(ReferenceDataDate(iso: iso))
    }

    @Test func startsLoadingWithoutADateLine() {
        let model = ReferenceAboutModel()
        #expect(model.state == .loading)
        #expect(model.dateLine() == nil)
    }

    @Test func showsTheDataDateOnceKnown() async throws {
        let model = ReferenceAboutModel()
        await model.load(from: InMemoryReferenceData(dataDate: try date("2026-10-18")))
        #expect(model.state == .available(try date("2026-10-18")))
    }

    @Test(arguments: [ReferenceUnavailableReason.missing, .unreadable, .unsupportedSchema])
    func showsUnavailableWhateverTheReason(reason: ReferenceUnavailableReason) async {
        let model = ReferenceAboutModel()
        await model.load(from: InMemoryReferenceData(status: .unavailable(reason)))
        #expect(model.state == .unavailable)
        #expect(model.dateLine() == nil)
    }

    @Test func noEnvironmentMeansUnavailable() async {
        let model = ReferenceAboutModel()
        await model.load(from: nil)
        #expect(model.state == .unavailable)
    }

    @Test func theDateLineIsLocalizedInEachLanguage() async throws {
        let model = ReferenceAboutModel()
        await model.load(from: InMemoryReferenceData(dataDate: try date("2026-10-18")))
        #expect(model.dateLine(bundle: try languageBundle("en"), locale: Locale(identifier: "en_US")) == "Data as of October 18, 2026")
        #expect(model.dateLine(bundle: try languageBundle("fr"), locale: Locale(identifier: "fr_FR")) == "Données du 18 octobre 2026")
    }

    @Test(arguments: [
        ("2026-01-01", "January 1, 2026"), ("2026-12-31", "December 31, 2026"),
        ("2028-02-29", "February 29, 2028"), ("2026-03-29", "March 29, 2026")
    ])
    func aCalendarDayKeepsItsDayAtYearAndMonthBoundaries(iso: String, expected: String) throws {
        // The model formats noon UTC in UTC, so no device time zone can move the day.
        #expect(ReferenceAboutModel.text(for: try date(iso), locale: Locale(identifier: "en_US")) == expected)
    }

    @Test func labelsOfTheSectionAreTranslatedAndStateTheSource() throws {
        for code in ["fr", "en"] {
            let bundle = try languageBundle(code)
            for key in ["about.reference.title", "about.reference.source", "about.reference.loading",
                        "about.reference.unavailable", "about.reference.notEndorsed", "about.reference.open"] {
                #expect(bundle.localizedString(forKey: key, value: nil, table: nil) != key, "\(code): \(key) is not translated")
            }
        }
        let english = try languageBundle("en").localizedString(forKey: "about.reference.notEndorsed", value: nil, table: nil)
        #expect(english.contains("ANSM") && english.contains("not affiliated"))
        let french = try languageBundle("fr").localizedString(forKey: "about.reference.source", value: nil, table: nil)
        #expect(french.contains("Base de Données Publique des Médicaments"))
        #expect(AboutView.referenceAddress == "base-donnees-publique.medicaments.gouv.fr")
    }
}
