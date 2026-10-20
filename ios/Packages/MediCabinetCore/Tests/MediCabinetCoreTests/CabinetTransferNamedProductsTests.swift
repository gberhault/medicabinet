import Foundation
import Testing
@testable import MediCabinetCore
import MediCabinetCoreTestSupport

/// Importing, backing up and exporting with the products the household named (archive `formatVersion` 2).
@Suite struct CabinetTransferNamedProductsTests {
    private let app = ArchiveApp(name: "MediCabinet", platform: "ios", version: "0.7.0", build: "10")
    private let exportedAt = ArchiveDates.date(milliseconds: 1_792_570_530_123)

    private struct Setup {
        let store: InMemoryArchiveStore
        let backups: InMemoryArchiveBackupStore
        let transfer: CabinetTransfer
        let golden: [UInt8]
        let goldenCabinet: CabinetContents
    }

    private func setup(initial: CabinetContents? = nil) throws -> Setup {
        let golden = try SharedFixtures.goldenArchive()
        let goldenCabinet = try ArchiveDecoder.decode(golden).cabinet
        let store = InMemoryArchiveStore(initial ?? CabinetContents(locations: [], boxes: []))
        let backups = InMemoryArchiveBackupStore()
        let transfer = CabinetTransfer(store: store, backups: backups, clock: FixedClock(exportedAt), app: app)
        return Setup(store: store, backups: backups, transfer: transfer, golden: golden, goldenCabinet: goldenCabinet)
    }

    private func smallCabinet(named name: String) -> CabinetContents {
        let location = Location(name: name, isDefault: true, createdAt: exportedAt)
        let box = Box(product: ProductRef(name: "Only box"), locationID: location.id, createdAt: exportedAt)
        return CabinetContents(locations: [ArchivedLocation(location: location)], boxes: [ArchivedBox(box: box)])
    }

    private func smallCabinetWithProducts(named name: String) -> CabinetContents {
        var cabinet = smallCabinet(named: name)
        // Created at different instants: the archive lists products by creation time, so the order is the same after a round trip.
        cabinet.namedProducts = [
            ArchivedNamedProduct(product: NamedProduct(gtin: "03401234567890", name: "Crème", createdAt: exportedAt)),
            ArchivedNamedProduct(
                product: NamedProduct(gtin: "03401234567891", name: "Forgotten", createdAt: exportedAt.addingTimeInterval(1)),
                deletedAt: exportedAt
            )
        ]
        return cabinet
    }

    @Test func aVersionOneFileLeavesTheNamedProductsToBeFilledAfterTheImport() async throws {
        let context = try setup(initial: smallCabinetWithProducts(named: "Old"))
        let version1 = try SharedFixtures.goldenArchive(version: 1)
        let preview = try await context.transfer.preview(version1)
        #expect(preview.archive.cabinet.namedProductsNeedFill)
        #expect(preview.file.namedProducts == 0)

        _ = try await context.transfer.apply(preview, reason: .beforeImport)

        #expect(try await context.store.backfillPending())
        let snapshot = try await context.store.snapshot()
        #expect(snapshot.namedProducts.isEmpty)
        #expect(snapshot.namedProductsNeedFill == false)
    }

    @Test func aVersionTwoFileKeepsItsOwnNamedProductsAndNeedsNoFill() async throws {
        let context = try setup(initial: smallCabinetWithProducts(named: "Old"))
        try await context.store.replace(with: {
            var pending = smallCabinetWithProducts(named: "Old")
            pending.namedProductsNeedFill = true
            return pending
        }()) { _ in }
        #expect(try await context.store.backfillPending())

        let preview = try await context.transfer.preview(context.golden)
        #expect(preview.archive.cabinet.namedProductsNeedFill == false)
        #expect(preview.file.namedProducts == 2)
        _ = try await context.transfer.apply(preview, reason: .beforeImport)

        #expect(try await context.store.backfillPending() == false)
        #expect(try await context.store.snapshot() == context.goldenCabinet)
        #expect(try await context.store.snapshot().namedProducts.count == 3)
    }

    @Test func theAutomaticBackupCarriesTheNamedProductsForgottenOnesIncluded() async throws {
        let old = smallCabinetWithProducts(named: "Old")
        let context = try setup(initial: old)
        let result = try await context.transfer.apply(try await context.transfer.preview(context.golden), reason: .beforeImport)
        let backup = try #require(result.backup)
        let saved = try ArchiveDecoder.decode(try await context.backups.read(backup.id))
        #expect(saved.sourceFormatVersion == 2)
        #expect(saved.cabinet == old)
        #expect(saved.cabinet.namedProducts.count == 2)

        // Restoring it brings the products back and asks for no fill.
        let restored = try await context.transfer.apply(
            try await context.transfer.preview(try await context.transfer.readBackup(backup.id)), reason: .beforeRestore
        )
        #expect(restored.imported.namedProducts == 1)
        #expect(try await context.store.snapshot() == old)
        #expect(try await context.store.backfillPending() == false)
    }

    @Test func anExportThenAnImportOnAnotherPhoneKeepsEveryNamedProduct() async throws {
        let original = smallCabinetWithProducts(named: "Home")
        let first = try setup(initial: original)
        let exported = try await first.transfer.exportArchive()
        let second = try setup()

        _ = try await second.transfer.apply(try await second.transfer.preview(exported), reason: .beforeImport)

        #expect(try await second.store.snapshot() == original)
        #expect(try await second.transfer.exportArchive() == exported)
    }
}
