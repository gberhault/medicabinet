import Foundation
import Testing
@testable import MediCabinet

@Suite struct AboutViewModelTests {
    private struct FakeInfo: AppInfo {
        let version: String
        let build: String
    }

    private func languageBundle(_ code: String) throws -> Bundle {
        let path = try #require(Bundle.main.path(forResource: code, ofType: "lproj"), "no \(code).lproj in the app")
        return try #require(Bundle(path: path))
    }

    @Test(arguments: ["fr", "en"])
    func versionLineShowsVersionAndBuildInEachLanguage(code: String) throws {
        let model = AboutViewModel(info: FakeInfo(version: "1.2.3", build: "45"), bundle: try languageBundle(code))
        #expect(model.versionLine == "Version 1.2.3 (45)")
    }

    @Test func disclaimerIsTranslatedAndStatesThereIsNoMedicalAdvice() throws {
        let french = try languageBundle("fr").localizedString(forKey: "about.disclaimer", value: nil, table: nil)
        let english = try languageBundle("en").localizedString(forKey: "about.disclaimer", value: nil, table: nil)
        #expect(french.contains("aucun conseil médical"))
        #expect(english.contains("no medical advice"))
    }

    @Test func bundleInfoReadsVersionAndBuildFromTheAppBundle() {
        let info = BundleAppInfo()
        #expect(info.version.wholeMatch(of: /\d+\.\d+\.\d+/) != nil, "version was \(info.version)")
        #expect(Int(info.build) != nil, "build was \(info.build)")
    }

    @Test func missingBundleKeysFallBackToAVisibleMarkerInsteadOfCrashing() throws {
        let empty = FileManager.default.temporaryDirectory.appendingPathComponent("EmptyBundle-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: empty, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: empty) }
        let info = BundleAppInfo(bundle: try #require(Bundle(url: empty)))
        #expect(info.version == "?")
        #expect(info.build == "?")
    }
}
