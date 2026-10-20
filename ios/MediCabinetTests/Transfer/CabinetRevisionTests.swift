import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

/// The scan session ends when an import or a restore replaced the cabinet (design §1.6, I7, I10).
@MainActor
@Suite struct CabinetRevisionTests {
    @Test func aBumpChangesTheValue() {
        let revision = CabinetRevision()
        revision.bump()
        revision.bump()
        #expect(revision.value == 2)
    }

    @Test func theScanSessionEndsWhenTheCabinetIsReplaced() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload())
        kit.model.showsSessionList = true
        #expect(kit.model.session.count == 1)

        kit.model.revision.bump()
        await kit.model.resetIfCabinetReplaced()

        #expect(kit.model.session.count == 0)
        #expect(kit.model.removalSession.count == 0)
        #expect(kit.model.notice == nil)
        #expect(!kit.model.showsSessionList)
        #expect(!kit.model.showsSummary)
    }

    @Test func nothingIsResetWithoutAReplace() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload())
        await kit.model.resetIfCabinetReplaced()
        #expect(kit.model.session.count == 1)
    }

    @Test func aSessionLocationThatNoLongerExistsFallsBackToTheDefault() async throws {
        let kit = try await ScanTestKit.make()
        let car = try await kit.locations.create(named: "Car")
        await kit.model.loadLocations()
        kit.model.selectLocation(car.id)
        try await kit.locations.deleteLocation(id: car.id, movingBoxesTo: nil)

        kit.model.revision.bump()
        await kit.model.resetIfCabinetReplaced()

        #expect(kit.model.sessionLocation?.id == kit.defaultLocation.id)
        #expect(kit.preferences.lastLocationID == car.id)
    }

    @Test func aRememberedLocationThatStillExistsIsKept() async throws {
        let kit = try await ScanTestKit.make()
        let car = try await kit.locations.create(named: "Car")
        await kit.model.loadLocations()
        kit.model.selectLocation(car.id)

        kit.model.revision.bump()
        await kit.model.resetIfCabinetReplaced()

        #expect(kit.model.sessionLocation?.id == car.id)
    }

    @Test func aSummaryOpenWhenTheCabinetIsReplacedIsClosed() async throws {
        let kit = try await ScanTestKit.make()
        await kit.see(ScanTestKit.payload())
        kit.model.finish()
        #expect(kit.model.showsSummary)

        kit.model.revision.bump()
        await kit.model.resetIfCabinetReplaced()

        #expect(!kit.model.showsSummary)
        #expect(kit.model.session.count == 0)
    }
}
