import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

@Suite struct ExpiryInputTests {
    @Test func nothingIsPreselected() {
        #expect(ExpiryInput().outcome == .incomplete)
        #expect(ExpiryChoice().resolved == .pending)
        #expect(ExpiryChoice().expiry == nil)
    }

    @Test func aMonthAndAYearAreEnoughAndGiveMonthPrecision() {
        let input = ExpiryInput(month: 3, year: 2027, day: nil)
        #expect(input.outcome == .valid(.date(2027, 3, nil)!))
    }

    @Test func aDayMakesItDayPrecise() {
        #expect(ExpiryInput(month: 3, year: 2027, day: 15).outcome == .valid(.date(2027, 3, 15)!))
    }

    @Test(arguments: [
        ExpiryInput(month: 4, year: 2027, day: 31), ExpiryInput(month: 2, year: 2027, day: 29),
        ExpiryInput(month: 13, year: 2027, day: nil), ExpiryInput(month: 3, year: 1999, day: nil),
        ExpiryInput(month: 3, year: 2100, day: nil)
    ])
    func anImpossibleDateIsInvalidNeverCorrected(input: ExpiryInput) {
        #expect(input.outcome == .invalid)
    }

    @Test func aLeapDayIsValid() {
        #expect(ExpiryInput(month: 2, year: 2028, day: 29).outcome == .valid(.date(2028, 2, 29)!))
    }

    @Test func halfAnInputIsIncomplete() {
        #expect(ExpiryInput(month: 3, year: nil, day: nil).outcome == .incomplete)
        #expect(ExpiryInput(month: nil, year: 2027, day: nil).outcome == .incomplete)
    }

    @Test func dateNotPrintedResolvesToUnknownWhateverWasTyped() {
        var choice = ExpiryChoice()
        choice.input = ExpiryInput(month: 13, year: 1, day: 99)
        choice.notPrinted = true
        #expect(choice.resolved == .unknown)
        #expect(choice.expiry == nil)
    }

    @Test func aValidInputResolvesToItsDate() {
        var choice = ExpiryChoice()
        choice.input = ExpiryInput(month: 5, year: 2029, day: nil)
        #expect(choice.resolved == .date(.date(2029, 5, nil)!))
        #expect(choice.expiry == .date(2029, 5, nil))
    }

    @Test func editingSeedsTheChoiceFromTheBox() {
        let known = ExpiryChoice(.date(2027, 3, 15), editing: true)
        #expect(known.input == ExpiryInput(month: 3, year: 2027, day: 15) && !known.notPrinted)
        let unknown = ExpiryChoice(nil, editing: true)
        #expect(unknown.notPrinted && unknown.resolved == .unknown)
        #expect(ExpiryChoice(nil, editing: false).resolved == .pending)
    }
}

@Suite struct ScanSessionTests {
    private func box(_ name: String, gtin: String? = "03400930000014", expiry: ExpiryDate? = nil) -> Box {
        Box(product: ProductRef(gtin: gtin, name: name), locationID: UUID(), expiry: expiry, createdAt: Date(timeIntervalSince1970: 0))
    }

    @Test func itListsTheNewestFirstAndCountsTheBoxesWithoutADate() {
        var session = ScanSession()
        let first = box("A", expiry: .date(2027, 3, nil))
        let second = box("B")
        session.add(first)
        session.add(second)
        #expect(session.count == 2)
        #expect(session.newestFirst.map(\.product.name) == ["B", "A"])
        #expect(session.unknownExpiryCount == 1)
    }

    @Test func itKnowsTheProductsAddedAndIgnoresBoxesWithoutGtin() {
        var session = ScanSession()
        session.add(box("A", gtin: "03400930000014"))
        session.add(box("B", gtin: nil))
        #expect(session.gtins == ["03400930000014"])
    }

    @Test func removingForgetsTheBoxAndReplacingKeepsItsPlace() {
        var session = ScanSession()
        let first = box("A"), second = box("B")
        session.add(first)
        session.add(second)
        var renamed = first
        renamed.product.name = "A2"
        session.replace(renamed)
        #expect(session.newestFirst.map(\.product.name) == ["B", "A2"])
        session.remove(second.id)
        #expect(session.count == 1)
        session.replace(box("unknown"))
        #expect(session.count == 1)
    }
}

@Suite struct ScanTextTests {
    private func bundle(_ code: String) throws -> Bundle {
        let path = try #require(Bundle.main.path(forResource: code, ofType: "lproj"), "no \(code).lproj in the app")
        return try #require(Bundle(path: path))
    }

    @Test func aDayPreciseDateIsFormattedInTheUsersLanguage() throws {
        let day = ExpiryDate.date(2027, 3, 15)
        #expect(ScanText.expiry(day, locale: Locale(identifier: "en_US"), bundle: try bundle("en")) == "Expires March 15, 2027")
        #expect(ScanText.expiry(day, locale: Locale(identifier: "fr_FR"), bundle: try bundle("fr")) == "Expire le 15 mars 2027")
    }

    @Test func aMonthPreciseDateSaysEndOfMonth() throws {
        let month = ExpiryDate.date(2027, 3, nil)
        #expect(ScanText.expiry(month, locale: Locale(identifier: "en_US"), bundle: try bundle("en")) == "Expires end of March 2027")
        #expect(ScanText.expiry(month, locale: Locale(identifier: "fr_FR"), bundle: try bundle("fr")) == "Expire fin mars 2027")
    }

    @Test func anUnknownExpiryIsSaidNotHidden() throws {
        #expect(ScanText.expiry(nil, bundle: try bundle("en")) == "Expiry unknown")
        #expect(ScanText.expiry(nil, bundle: try bundle("fr")) == "Péremption inconnue")
    }

    @Test func theAnnouncementNamesTheBoxAndItsExpiry() throws {
        let text = ScanText.announcement(name: "DOLIPRANE", expiry: nil, bundle: try bundle("en"))
        #expect(text == "Added: DOLIPRANE, Expiry unknown")
    }

    @Test func formatFillsPositionalArguments() throws {
        #expect(ScanText.format("scan.count", 3, bundle: try bundle("en")) == "Boxes added: 3")
        #expect(ScanText.format("scan.location.label", "Kitchen", bundle: try bundle("fr")) == "Lieu : Kitchen")
    }
}

@MainActor
@Suite struct ScanPreferencesTests {
    private func defaults() throws -> UserDefaults {
        let name = "ScanPreferencesTests-\(UUID().uuidString)"
        let suite = try #require(UserDefaults(suiteName: name))
        suite.removePersistentDomain(forName: name)
        return suite
    }

    @Test func soundIsOffAndThereIsNoLocationByDefault() throws {
        let preferences = UserDefaultsScanPreferences(defaults: try defaults())
        #expect(!preferences.soundEnabled)
        #expect(preferences.lastLocationID == nil)
    }

    @Test func valuesSurviveANewInstance() throws {
        let store = try defaults()
        let id = UUID()
        let first = UserDefaultsScanPreferences(defaults: store)
        first.soundEnabled = true
        first.lastLocationID = id
        let second = UserDefaultsScanPreferences(defaults: store)
        #expect(second.soundEnabled)
        #expect(second.lastLocationID == id)
        second.lastLocationID = nil
        #expect(UserDefaultsScanPreferences(defaults: store).lastLocationID == nil)
    }

    @Test func aCorruptStoredLocationIsIgnored() throws {
        let store = try defaults()
        store.set("not a uuid", forKey: UserDefaultsScanPreferences.locationKey)
        #expect(UserDefaultsScanPreferences(defaults: store).lastLocationID == nil)
    }

    @Test func thePrivacyManifestDeclaresTheUserDefaultsReason() throws {
        let url = try #require(Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"))
        let plist = try #require(PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: Any])
        let types = try #require(plist["NSPrivacyAccessedAPITypes"] as? [[String: Any]])
        let defaults = try #require(types.first { $0["NSPrivacyAccessedAPIType"] as? String == "NSPrivacyAccessedAPICategoryUserDefaults" })
        #expect(defaults["NSPrivacyAccessedAPITypeReasons"] as? [String] == ["CA92.1"])
    }
}

@MainActor
@Suite struct ScanServicesTests {
    private let clock = FixedClock(Date(timeIntervalSince1970: 0))

    @Test func withoutTestArgumentsTheRealCameraAndPermissionAreUsed() {
        let services = ScanServices.make(clock: clock, arguments: [], environment: [:])
        #expect(services.permission is SystemCameraPermission)
        #expect(services.preferences is UserDefaultsScanPreferences)
    }

    @Test(arguments: [
        ("notDetermined", CameraAccess.notDetermined), ("authorized", .authorized), ("denied", .denied),
        ("restricted", .restricted), ("unsupported", .authorized)
    ] as [(String, CameraAccess)])
    func uiTestsFixTheCameraAccess(argument: String, expected: CameraAccess) {
        let services = ScanServices.make(
            clock: clock, arguments: [ScanServices.cameraAccessArgument, argument], environment: [:]
        )
        #expect(services.permission.access == expected)
        #expect(services.preferences is InMemoryScanPreferences)
    }

    @Test func uiTestsReplayThePayloadsOfTheEnvironment() {
        let services = ScanServices.make(
            clock: clock, arguments: [ScanServices.cameraAccessArgument, "authorized"],
            environment: [InjectedCodeScanner.environmentKey: "A|B"]
        )
        let scanner = services.makeScanner()
        #expect((scanner as? InjectedCodeScanner)?.scriptForTesting == ["A", "B"])
    }

    @Test func anUnsupportedDeviceFailsToStart() {
        let services = ScanServices.make(
            clock: clock, arguments: [ScanServices.cameraAccessArgument, "unsupported"], environment: [:]
        )
        #expect(throws: CodeScannerError.unsupported) { try services.makeScanner().start() }
    }

    @Test func theUnsupportedScannerReportsItselfAndHasNothingToShow() {
        let scanner = UnsupportedCodeScanner()
        #expect(throws: CodeScannerError.unsupported) { try scanner.start() }
        scanner.stop()
        _ = scanner.preview
    }

    @Test func theUnknownProductLookupKnowsNothing() async throws {
        #expect(try await UnknownProductLookup().product(gtin: "03400930000014") == nil)
    }
}

#if DEBUG
@MainActor
@Suite struct PayloadCaptureModelTests {
    @Test func itListsShapesNewestFirstWithoutDuplicatesOrValues() async throws {
        let scanner = InjectedCodeScanner(
            script: ["010340093000001417270300101A2B3C\u{1D}21SECRET123", "3400930000014"],
            interval: .milliseconds(20), clock: FixedClock(Date(timeIntervalSince1970: 0))
        )
        let model = PayloadCaptureModel()
        let task = Task { await model.run(scanner) }
        for _ in 0..<400 where model.shapes.count < 2 { try await Task.sleep(for: .milliseconds(10)) }
        task.cancel()
        await task.value
        #expect(model.shapes == ["EAN13", "01 17 10(6) GS 21(9)"])
        #expect(!model.shapes.joined().contains("SECRET123"))
    }

    @Test func itRemembersAtMostFiftyShapes() {
        let model = PayloadCaptureModel()
        for index in 0..<60 { model.record("shape \(index)") }
        model.record("shape 59")
        #expect(model.shapes.count == 50)
        #expect(model.shapes.first == "shape 59")
    }

    @Test func anUnavailableCameraIsReportedNotHidden() async {
        let scanner = InjectedCodeScanner(script: [], startError: .unsupported, clock: FixedClock(Date(timeIntervalSince1970: 0)))
        let model = PayloadCaptureModel()
        await model.run(scanner)
        #expect(model.error == .unsupported)
    }
}
#endif
