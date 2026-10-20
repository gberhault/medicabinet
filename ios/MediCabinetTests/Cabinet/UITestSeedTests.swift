import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

@MainActor
@Suite struct UITestSeedTests {
    private let json = """
    [{"name":"Doliprane","gtin":"03400930000014","category":"pain-fever","expiryInDays":-2,"opened":true,"lot":"L1"},
     {"name":"Thermometer","location":"Car"},
     {"name":"Monthly","expiryInDays":-45,"monthPrecision":true}]
    """

    @Test func theSeedIsReadOnlyWithTemporaryStorage() {
        let environment = [UITestSeed.environmentKey: json]
        #expect(UITestSeed.items(from: environment, arguments: []).isEmpty)
        #expect(UITestSeed.items(from: environment, arguments: [AppLaunch.temporaryStorageArgument]).count == 3)
    }

    @Test func aMissingOrMalformedSeedSeedsNothing() {
        let arguments = [AppLaunch.temporaryStorageArgument]
        #expect(UITestSeed.items(from: [:], arguments: arguments).isEmpty)
        #expect(UITestSeed.items(from: [UITestSeed.environmentKey: "not json"], arguments: arguments).isEmpty)
    }

    @Test func seededBoxesAreInsertedWithRelativeExpiriesAndNamedLocations() async throws {
        let kit = try await CabinetTestKit()
        let items = UITestSeed.items(from: [UITestSeed.environmentKey: json], arguments: [AppLaunch.temporaryStorageArgument])
        await UITestSeed.apply(items, to: kit.environment)

        let boxes = try await kit.boxes.activeBoxes()
        #expect(boxes.count == 3)
        let doliprane = try #require(boxes.first { $0.product.name == "Doliprane" })
        #expect(doliprane.expiry == kit.expiry(inDays: -2))
        #expect(doliprane.category == .painFever)
        #expect(doliprane.isOpened)
        #expect(doliprane.lot == "L1")
        #expect(doliprane.locationID == kit.home.id, "no location named: the default one")
        let thermometer = try #require(boxes.first { $0.product.name == "Thermometer" })
        #expect(thermometer.expiry == nil, "no expiry in the seed means an unknown expiry")
        let monthly = try #require(boxes.first { $0.product.name == "Monthly" })
        let day = kit.environment.today().adding(days: -45)
        #expect(monthly.expiry == (try ExpiryDate.make(year: day.year, month: day.month, day: nil)), "month precision, no day")
        let car = try #require(try await kit.locations.all().first { $0.name == "Car" })
        #expect(thermometer.locationID == car.id)
    }

    @Test func anEmptySeedChangesNothing() async throws {
        let kit = try await CabinetTestKit()
        await UITestSeed.apply([], to: kit.environment)
        #expect(try await kit.boxes.activeBoxes().isEmpty)
    }

    @Test func aSeedThatCannotBeWrittenIsSkippedWithoutCrashing() async throws {
        let kit = try await CabinetTestKit()
        try kit.database.writer.close()
        let items = UITestSeed.items(from: [UITestSeed.environmentKey: json], arguments: [AppLaunch.temporaryStorageArgument])
        await UITestSeed.apply(items, to: kit.environment)
    }
}
