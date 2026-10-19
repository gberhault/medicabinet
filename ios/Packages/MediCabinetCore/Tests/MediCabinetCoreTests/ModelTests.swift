import Foundation
import Testing
@testable import MediCabinetCore
import MediCabinetCoreTestSupport

@Suite struct ModelTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func categoryKeyRoundTripsAndHasAnUncategorizedValue() throws {
        let category = CategoryID(rawValue: "pain-fever")
        #expect(category.rawValue == "pain-fever")
        #expect(CategoryID.uncategorized.rawValue == "uncategorized")
        let data = try JSONEncoder().encode(category)
        #expect(try JSONDecoder().decode(CategoryID.self, from: data) == category)
    }

    @Test func locationDefaultsUpdatedAtToCreatedAtAndHasStableIdentity() {
        let location = Location(name: "Bathroom", createdAt: now)
        #expect(location.updatedAt == now)
        #expect(!location.isDefault)
        #expect(Location(name: "Bathroom", createdAt: now).id != location.id)
        #expect(Location(id: location.id, name: "X", createdAt: now).id == location.id)
    }

    @Test func newBoxIsInTheCabinetWithUnknownExpiryAndUncategorized() {
        let box = Box(product: ProductRef(name: "Doliprane"), locationID: UUID(), createdAt: now)
        #expect(box.isInCabinet)
        #expect(box.expiry == nil)
        #expect(box.category == .uncategorized)
        #expect(!box.categoryOverridden)
        #expect(!box.isOpened)
        #expect(box.updatedAt == now)
    }

    @Test func boxWithRemovalIsNotInTheCabinet() {
        let removal = Box.Removal(reason: .thrownAway, date: now)
        let box = Box(product: ProductRef(name: "Doliprane"), locationID: UUID(), createdAt: now, removal: removal)
        #expect(!box.isInCabinet)
        #expect(box.removal?.reason == .thrownAway)
    }

    @Test func boxKeepsAKnownExpiryUnchanged() throws {
        let expiry = try ExpiryDate.make(year: 2027, month: 2, day: nil)
        let box = Box(product: ProductRef(gtin: "03400930000001", name: "A"), locationID: UUID(), expiry: expiry, createdAt: now)
        #expect(box.expiry == expiry)
        #expect(box.product.gtin == "03400930000001")
    }

    @Test func removalReasonsAreStableStrings() {
        #expect(RemovalReason.allCases.map(\.rawValue) == ["usedUp", "thrownAway"])
    }

    @Test func expiryStatusHasAnExplicitUnknownCase() {
        #expect(ExpiryStatus.allCases.contains(.unknown))
        #expect(ExpiryStatus.unknown != .ok)
    }

    @Test func theSharedReadmeListsEveryCrossPlatformName() throws {
        let readme = try #require(String(bytes: SharedFixtures.data("README.md"), encoding: .utf8))
        let names = RemovalReason.allCases.map(\.rawValue) + ExpiryStatus.allCases.map(\.rawValue)
            + [CategoryID.uncategorized.rawValue, "invalidYear", "invalidMonth", "invalidDay"]
            + ["notFound", "unknownLocation", "duplicate", "duplicateDefault", "duplicateSerial"]
            + ["duplicateName", "defaultLocation", "locationNotEmpty", "emptyName", "nameTooLong"]
            + ["remove", "choose", "alreadyRemoved", "notInCabinet"]
            + ["invalidName", "invalidCode", "add", "complete", "confirmAnother", "alreadyInCabinet", "removedEarlier"]
        for name in names {
            #expect(readme.contains("`\(name)`"), "fixtures/README.md does not list `\(name)`")
        }
    }
}
