import Foundation
import Testing
@testable import MediCabinetCore
import MediCabinetCoreTestSupport

@Suite struct CabinetTransferTests {
    private let app = ArchiveApp(name: "MediCabinet", platform: "ios", version: "0.3.0", build: "3")
    private let exportedAt = ArchiveDates.date(milliseconds: 1_792_397_730_123)

    private struct Setup {
        let store: InMemoryArchiveStore
        let backups: InMemoryArchiveBackupStore
        let transfer: CabinetTransfer
        let golden: [UInt8]
        let goldenCabinet: CabinetContents
    }

    /// A transfer whose store already holds `initial` (nothing when `nil`).
    private func setup(initial: CabinetContents? = nil, keeping: Int = CabinetTransfer.defaultKeepCount) throws -> Setup {
        let golden = try SharedFixtures.goldenArchive()
        let goldenCabinet = try ArchiveDecoder.decode(golden).cabinet
        let store = InMemoryArchiveStore(initial ?? CabinetContents(locations: [], boxes: []))
        let backups = InMemoryArchiveBackupStore()
        let transfer = CabinetTransfer(
            store: store, backups: backups, clock: FixedClock(exportedAt), app: app, keepCount: keeping
        )
        return Setup(store: store, backups: backups, transfer: transfer, golden: golden, goldenCabinet: goldenCabinet)
    }

    private func smallCabinet(named name: String) -> CabinetContents {
        let location = Location(name: name, isDefault: true, createdAt: exportedAt)
        let box = Box(product: ProductRef(name: "Only box"), locationID: location.id, createdAt: exportedAt)
        return CabinetContents(locations: [ArchivedLocation(location: location)], boxes: [ArchivedBox(box: box)])
    }

    // MARK: Export

    @Test func exportingTheGoldenCabinetGivesTheGoldenFileByteForByte() async throws {
        let context = try setup()
        try await context.store.replace(with: context.goldenCabinet) { _ in }
        #expect(try await context.transfer.exportArchive() == context.golden)
    }

    @Test func anEmptyCabinetCanBeExported() async throws {
        let home = Location(name: "Home", isDefault: true, createdAt: exportedAt)
        let context = try setup(initial: CabinetContents(locations: [ArchivedLocation(location: home)], boxes: []))
        let archive = try ArchiveDecoder.decode(try await context.transfer.exportArchive())
        #expect(archive.cabinet.summary.inCabinet == 0)
        #expect(archive.app == app)
    }

    @Test func aCabinetTheArchiveWouldRefuseIsNotExported() async throws {
        let context = try setup(initial: CabinetContents(locations: [], boxes: []))
        await #expect(throws: ArchiveError(.defaultLocation, path: "locations")) { try await context.transfer.exportArchive() }
    }

    @Test func theCurrentSummaryDescribesTheStore() async throws {
        let context = try setup(initial: smallCabinet(named: "Home"))
        let expected = CabinetSummary(locations: 1, inCabinet: 1, removed: 0, deleted: 0, unknownExpiry: 1)
        #expect(try await context.transfer.currentSummary() == expected)
    }

    // MARK: Preview

    @Test func aPreviewComparesTheFileWithThePhone() async throws {
        let context = try setup(initial: smallCabinet(named: "Home"))
        let preview = try await context.transfer.preview(context.golden)
        #expect(preview.file == context.goldenCabinet.summary)
        #expect(preview.current.inCabinet == 1)
        #expect(preview.willBackUp)
        #expect(preview.archive.sourceFormatVersion == 1)
    }

    @Test func aPhoneWithNothingToLoseNeedsNoBackup() async throws {
        let home = ArchivedLocation(location: Location(name: "Home", isDefault: true, createdAt: exportedAt))
        let empty = CabinetContents(locations: [home], boxes: [])
        #expect(try await setup(initial: empty).transfer.preview(try SharedFixtures.goldenArchive()).willBackUp == false)
        #expect(try await setup().transfer.preview(try SharedFixtures.goldenArchive()).willBackUp == false)
    }

    @Test func aFileTheDecoderRefusesIsNotPreviewed() async throws {
        let context = try setup()
        await #expect(throws: ArchiveError(.notArchive)) { try await context.transfer.preview(Array("{}".utf8)) }
        await #expect(throws: ArchiveError(.notJSON)) { try await context.transfer.preview([]) }
    }

    // MARK: Apply

    @Test func applyBacksUpTheOldCabinetThenReplacesIt() async throws {
        let old = smallCabinet(named: "Old")
        let context = try setup(initial: old)
        let result = try await context.transfer.apply(try await context.transfer.preview(context.golden), reason: .beforeImport)
        #expect(try await context.store.snapshot() == context.goldenCabinet)
        #expect(result.imported == context.goldenCabinet.summary)
        let backup = try #require(result.backup)
        #expect(backup.reason == .beforeImport)
        #expect(backup.takenAt == exportedAt)
        let saved = try ArchiveDecoder.decode(try await context.backups.read(backup.id))
        #expect(saved.cabinet == old)
        #expect(saved.exportedAt == exportedAt)
        #expect(saved.app == app)
        #expect(result.pruneFailed == false)
    }

    @Test func nothingIsBackedUpWhenThereIsNothingToLose() async throws {
        let context = try setup()
        let result = try await context.transfer.apply(try await context.transfer.preview(context.golden), reason: .beforeImport)
        #expect(result.backup == nil)
        #expect(context.backups.count == 0)
        #expect(try await context.store.snapshot() == context.goldenCabinet)
    }

    @Test func theBackupHoldsTheCabinetAsItIsWhenTheReplaceStartsNotWhenTheFileWasPreviewed() async throws {
        let context = try setup(initial: smallCabinet(named: "At preview time"))
        let preview = try await context.transfer.preview(context.golden)
        let later = smallCabinet(named: "Added after the preview")
        try await context.store.replace(with: later) { _ in }
        let result = try await context.transfer.apply(preview, reason: .beforeImport)
        let saved = try ArchiveDecoder.decode(try await context.backups.read(try #require(result.backup).id))
        #expect(saved.cabinet == later)
    }

    @Test func aFailedBackupStopsTheImportAndLeavesTheCabinetAlone() async throws {
        let old = smallCabinet(named: "Old")
        let context = try setup(initial: old)
        let preview = try await context.transfer.preview(context.golden)
        context.backups.setFailingWrites(true)
        await #expect(throws: TransferError.backupFailed) { try await context.transfer.apply(preview, reason: .beforeImport) }
        #expect(try await context.store.snapshot() == old)
        #expect(context.backups.count == 0)
    }

    @Test func aStoreThatRefusesTheReplaceLeavesTheCabinetAlone() async throws {
        let old = smallCabinet(named: "Old")
        let context = try setup(initial: old)
        let preview = try await context.transfer.preview(context.golden)
        let refusing = CabinetTransfer(store: RefusingStore(), backups: context.backups, clock: FixedClock(exportedAt), app: app)
        await #expect(throws: RefusingStore.Refusal.self) { try await refusing.apply(preview, reason: .beforeImport) }
        #expect(try await context.store.snapshot() == old)
        #expect(context.backups.count == 0)
    }

    @Test func failingToPruneOldBackupsDoesNotFailTheImport() async throws {
        let context = try setup(initial: smallCabinet(named: "Old"))
        context.backups.setFailingPrunes(true)
        let result = try await context.transfer.apply(try await context.transfer.preview(context.golden), reason: .beforeImport)
        #expect(result.pruneFailed)
        #expect(try await context.store.snapshot() == context.goldenCabinet)
    }

    @Test func onlyTheNewestBackupsAreKept() async throws {
        let context = try setup(initial: smallCabinet(named: "Zero"))
        for number in 1...5 {
            let preview = try await context.transfer.preview(context.golden)
            _ = try await context.transfer.apply(preview, reason: .beforeImport)
            // Put something else in the cabinet so the next import has something to back up.
            try await context.store.replace(with: smallCabinet(named: "Round \(number)")) { _ in }
        }
        #expect(try await context.transfer.backups().count == CabinetTransfer.defaultKeepCount)
    }

    // MARK: Restore

    @Test func restoringABackupBringsTheOldCabinetBackAndBacksUpTheCurrentOne() async throws {
        let old = smallCabinet(named: "Before the import")
        let context = try setup(initial: old)
        let imported = try await context.transfer.apply(try await context.transfer.preview(context.golden), reason: .beforeImport)
        let backup = try #require(imported.backup)

        let bytes = try await context.transfer.readBackup(backup.id)
        let preview = try await context.transfer.preview(bytes)
        let restored = try await context.transfer.apply(preview, reason: .beforeRestore)

        #expect(try await context.store.snapshot() == old)
        #expect(try #require(restored.backup).reason == .beforeRestore)
        let listed = try await context.transfer.backups()
        #expect(Set(listed.map(\.reason)) == [.beforeImport, .beforeRestore])
    }

    // MARK: File name, summary

    @Test(arguments: [
        ("2026-10-19T08:15:30.123Z", "Europe/Paris", "MediCabinet-2026-10-19.medicabinet"),
        ("2026-10-19T23:30:00.000Z", "Europe/Paris", "MediCabinet-2026-10-20.medicabinet"),
        ("2026-10-19T23:30:00.000Z", "America/Los_Angeles", "MediCabinet-2026-10-19.medicabinet"),
        ("2026-12-31T23:30:00.000Z", "UTC", "MediCabinet-2026-12-31.medicabinet"),
        ("2027-01-01T00:00:00.000Z", "UTC", "MediCabinet-2027-01-01.medicabinet"),
        ("2000-02-29T12:00:00.000Z", "UTC", "MediCabinet-2000-02-29.medicabinet")
    ])
    func theFileNameUsesTheLocalDayOfTheExport(_ instant: String, _ zone: String, _ expected: String) throws {
        let date = try #require(ArchiveDates.parse(instant))
        let timeZone = try #require(TimeZone(identifier: zone))
        #expect(CabinetTransfer.fileName(for: date, in: timeZone) == expected)
    }

    @Test func aSingleDefaultLocationAndNoBoxNeedsNoBackup() {
        #expect(CabinetSummary(locations: 1, inCabinet: 0, removed: 0, deleted: 0, unknownExpiry: 0).needsBackup == false)
        #expect(CabinetSummary(locations: 0, inCabinet: 0, removed: 0, deleted: 0, unknownExpiry: 0).needsBackup == false)
        #expect(CabinetSummary(locations: 0, inCabinet: 1, removed: 0, deleted: 0, unknownExpiry: 0).needsBackup)
        #expect(CabinetSummary(locations: 2, inCabinet: 0, removed: 0, deleted: 0, unknownExpiry: 0).needsBackup)
        #expect(CabinetSummary(locations: 1, inCabinet: 1, removed: 0, deleted: 0, unknownExpiry: 0).needsBackup)
        #expect(CabinetSummary(locations: 1, inCabinet: 0, removed: 1, deleted: 0, unknownExpiry: 0).needsBackup)
        #expect(CabinetSummary(locations: 1, inCabinet: 0, removed: 0, deleted: 1, unknownExpiry: 0).needsBackup)
    }

    @Test func theSnapshotSummaryCountsEveryKindOfBox() throws {
        let context = try setup()
        let summary = context.goldenCabinet.summary
        #expect(summary.locations == 3)
        #expect(summary.removed == 2)
        #expect(summary.deleted == 1)
        #expect(summary.inCabinet == 9)
        #expect(summary.unknownExpiry == 2)
        #expect(context.goldenCabinet.needsBackup)
    }
}

/// A store that cannot replace anything, like a full disk.
private struct RefusingStore: CabinetArchiveStore {
    struct Refusal: Error {}

    func snapshot() async throws -> CabinetContents {
        CabinetContents(locations: [], boxes: [])
    }

    func replace(with snapshot: CabinetContents, beforeReplacing: @Sendable (CabinetContents) throws -> Void) async throws {
        throw Refusal()
    }
}
