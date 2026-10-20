import Foundation
import GRDB
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

/// The contract reads the backfill marker through the real named-product repository.
extension GRDBCabinetArchiveStore: ArchiveStoreUnderContract {
    public func backfillPending() async throws -> Bool {
        try await GRDBNamedProductRepository(database: database, clock: FixedClock(Date())).backfillPending()
    }
}

@Suite struct GRDBCabinetArchiveStoreTests {
    /// The shared store contract (`fixtures/archive-store-contract.json`) on a real database file.
    @Test(arguments: ArchiveStoreContract.checks)
    func grdbSatisfiesTheStoreContract(check: ArchiveStoreContract.Check) async throws {
        try await check.run {
            let (database, _) = try AppDatabase.temporary()
            return GRDBCabinetArchiveStore(database: database)
        }
    }

    @Test func contractChecksMatchTheSharedList() throws {
        #expect(ArchiveStoreContract.checks.map(\.name) == (try SharedFixtures.archiveStoreContractCheckNames()))
    }

    // MARK: Round trip (F7)

    /// Every field variant of the golden file goes into SQLite and comes back out byte for byte: ids, dates at
    /// millisecond precision, month-precision and unknown expiries, unknown categories, tombstones.
    @Test func theGoldenCabinetRoundTripsThroughTheDatabaseByteForByte() async throws {
        let golden = try SharedFixtures.goldenArchive()
        let archive = try ArchiveDecoder.decode(golden)
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transfer = TransferHarness.transfer(
            database: database, backups: directory.appendingPathComponent("Backups"), at: archive.exportedAt, app: archive.app
        )

        _ = try await transfer.apply(try await transfer.preview(golden), reason: .beforeImport)

        #expect(try await transfer.exportArchive() == golden)
    }

    @Test func anExportAppliedOnAFreshDatabaseGivesAnEqualCabinet() async throws {
        let golden = try SharedFixtures.goldenArchive()
        let archive = try ArchiveDecoder.decode(golden)
        let first = try TransferHarness.temporary(at: archive.exportedAt, app: archive.app)
        let second = try TransferHarness.temporary(at: archive.exportedAt, app: archive.app)
        defer { first.cleanUp(); second.cleanUp() }
        _ = try await first.transfer.apply(try await first.transfer.preview(golden), reason: .beforeImport)

        let exported = try await first.transfer.exportArchive()
        _ = try await second.transfer.apply(try await second.transfer.preview(exported), reason: .beforeImport)

        #expect(try await second.store.snapshot() == (try await first.store.snapshot()))
        #expect(try await second.store.snapshot() == archive.cabinet)
    }

    @Test func theExpirySortKeyIsRecomputedFromTheExpiryParts() async throws {
        let golden = try SharedFixtures.goldenArchive()
        let archive = try ArchiveDecoder.decode(golden)
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        try await GRDBCabinetArchiveStore(database: database).replace(with: archive.cabinet) { _ in }

        let stored = try await database.writer.read { db in
            try Row.fetchAll(db, sql: "SELECT id, expirySortKey FROM box").map { row -> (String, String?) in
                (row["id"], row["expirySortKey"])
            }
        }
        let expected = Dictionary(uniqueKeysWithValues: archive.cabinet.boxes.map { ($0.box.id.uuidString, $0.box.expiry?.sortKey) })
        #expect(stored.count == expected.count)
        for (id, key) in stored { #expect(expected[id] == key, "box \(id)") }
        #expect(stored.contains { $0.1 == nil })
    }

    @Test func importedBoxesAndLocationsAreWhatTheRepositoriesShow() async throws {
        let golden = try SharedFixtures.goldenArchive()
        let archive = try ArchiveDecoder.decode(golden)
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        try await GRDBCabinetArchiveStore(database: database).replace(with: archive.cabinet) { _ in }
        let clock = FixedClock(archive.exportedAt)

        let active = try await GRDBBoxRepository(database: database, clock: clock).activeBoxes()
        let live = try await GRDBLocationRepository(database: database, clock: clock).all()
        #expect(active.count == archive.cabinet.summary.inCabinet)
        #expect(live.count == archive.cabinet.summary.locations)
    }

    // MARK: Named products

    /// The golden file carries a live and a forgotten product with one GTIN, and a live one with a user category: all of
    /// them reach the table, and the repositories see only the live ones.
    @Test func namedProductsOfTheGoldenFileLandInTheTableAndTheRepositoryShowsTheLiveOnes() async throws {
        let golden = try SharedFixtures.goldenArchive()
        let archive = try ArchiveDecoder.decode(golden)
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        try await GRDBCabinetArchiveStore(database: database).replace(with: archive.cabinet) { _ in }

        let rows = try await database.writer.read { db in try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM namedProduct") }
        #expect(rows == 3)
        let repository = GRDBNamedProductRepository(database: database, clock: FixedClock(archive.exportedAt))
        let shown = try await repository.namedProduct(gtin: "03400936404571")
        #expect(shown?.name == "Produit 2")
        let chosen = try await repository.namedProduct(gtin: "03401234567890")
        #expect(chosen?.category.rawValue == "skin-wound")
        #expect(chosen?.categoryOverridden == true)
        #expect(try await repository.backfillPending() == false)
    }

    /// A version 1 file has no named products: the import leaves the table empty and the fill pending, and the
    /// snapshot still reports no fill (the marker is the store's, not the cabinet's).
    @Test func aVersionOneImportSetsTheMarkerAndClearsTheTable() async throws {
        let old = try ArchiveDecoder.decode(try SharedFixtures.goldenArchive(version: 1))
        let current = try ArchiveDecoder.decode(try SharedFixtures.goldenArchive())
        #expect(old.cabinet.namedProductsNeedFill)
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = GRDBCabinetArchiveStore(database: database)
        try await store.replace(with: current.cabinet) { _ in }
        #expect(try await store.backfillPending() == false)

        try await store.replace(with: old.cabinet) { _ in }

        let snapshot = try await store.snapshot()
        #expect(snapshot.namedProducts.isEmpty)
        #expect(snapshot.namedProductsNeedFill == false)
        #expect(try await store.backfillPending())
    }

    /// The same GTIN live twice is refused before anything is touched, as the repositories name it (`duplicate`), with the
    /// table and the marker untouched.
    @Test func twoLiveProductsWithOneGTINAreARepositoryErrorAndChangeNothing() async throws {
        let archive = try ArchiveDecoder.decode(try SharedFixtures.goldenArchive())
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = GRDBCabinetArchiveStore(database: database)
        try await store.replace(with: archive.cabinet) { _ in }
        let before = try await store.snapshot()
        var twins = archive.cabinet
        twins.namedProductsNeedFill = true
        twins.namedProducts = (1...2).map { number in
            ArchivedNamedProduct(product: NamedProduct(gtin: "03401234567890", name: "Twin \(number)", createdAt: archive.exportedAt))
        }
        do {
            try await store.replace(with: twins) { _ in }
            Issue.record("the replace should have failed")
        } catch {
            #expect(String(reflecting: error) == String(reflecting: RepositoryError.duplicate))
        }
        #expect(try await store.snapshot() == before)
        #expect(try await store.backfillPending() == false)
    }

    /// A row the table itself refuses (the CHECK on the name length is the backstop of the file validator) fails the
    /// transaction and rolls the whole replace back, the marker included.
    @Test func aNamedProductInsertFailingMidwayRollsTheWholeReplaceBack() async throws {
        let archive = try ArchiveDecoder.decode(try SharedFixtures.goldenArchive())
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = GRDBCabinetArchiveStore(database: database)
        try await store.replace(with: archive.cabinet) { _ in }
        let before = try await store.snapshot()
        var tooLong = archive.cabinet
        tooLong.namedProductsNeedFill = true
        tooLong.namedProducts[0].product.name = String(repeating: "n", count: 201)

        await #expect(throws: (any Error).self) { try await store.replace(with: tooLong) { _ in } }

        #expect(try await store.snapshot() == before)
        #expect(try await store.backfillPending() == false)
    }

    // MARK: Rollback (I6)

    /// The snapshot passes the up-front checks but breaks the unique live serial while the rows are inserted, after
    /// the old rows were deleted: everything rolls back.
    @Test func anInsertFailingMidwayRollsTheWholeReplaceBack() async throws {
        let golden = try SharedFixtures.goldenArchive()
        let archive = try ArchiveDecoder.decode(golden)
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = GRDBCabinetArchiveStore(database: database)
        try await store.replace(with: archive.cabinet) { _ in }
        let before = try await store.snapshot()

        let location = Location(name: "New", isDefault: true, createdAt: archive.exportedAt)
        func twin(_ index: Int) -> ArchivedBox {
            ArchivedBox(box: Box(
                product: ProductRef(gtin: "03400936404564", name: "Twin \(index)"), locationID: location.id,
                serial: "SAME", createdAt: archive.exportedAt.addingTimeInterval(Double(index))
            ))
        }
        let clashing = CabinetContents(locations: [ArchivedLocation(location: location)], boxes: [twin(1), twin(2)])

        await #expect(throws: (any Error).self) { try await store.replace(with: clashing) { _ in } }
        #expect(try await store.snapshot() == before)
    }

    @Test func aDuplicateSerialIsReportedAsARepositoryError() async throws {
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let location = Location(name: "New", isDefault: true, createdAt: now)
        let twins = (1...2).map { index in
            ArchivedBox(box: Box(
                product: ProductRef(gtin: "03400936404564", name: "Twin"), locationID: location.id, serial: "SAME",
                createdAt: now.addingTimeInterval(Double(index))
            ))
        }
        let store = GRDBCabinetArchiveStore(database: database)
        do {
            try await store.replace(with: CabinetContents(locations: [ArchivedLocation(location: location)], boxes: twins)) { _ in }
            Issue.record("the replace should have failed")
        } catch {
            #expect(String(reflecting: error) == String(reflecting: RepositoryError.duplicateSerial))
        }
    }

    // MARK: Performance (§9)

    /// Export and import (with its backup) of a 1 000-box cabinet, each ≤ 600 ms, best of 5 runs.
    @Test func exportingAndImportingAThousandBoxesStaysUnderTheBudget() async throws {
        let bytes = Array(SyntheticArchive.json(locations: 3, boxes: 1_000).utf8)
        let harness = try TransferHarness.temporary(at: Date(timeIntervalSince1970: 1_800_000_000))
        defer { harness.cleanUp() }
        let transfer = harness.transfer
        _ = try await transfer.apply(try await transfer.preview(bytes), reason: .beforeImport)

        var exports: [Duration] = []
        var imports: [Duration] = []
        for _ in 0..<5 {
            var started = ContinuousClock.now
            let exported = try await transfer.exportArchive()
            exports.append(ContinuousClock.now - started)
            let preview = try await transfer.preview(exported)
            started = ContinuousClock.now
            let result = try await transfer.apply(preview, reason: .beforeImport)
            imports.append(ContinuousClock.now - started)
            #expect(result.backup != nil)
        }
        let (bestExport, bestImport) = (exports.min() ?? .zero, imports.min() ?? .zero)
        print("exportArchive 1 000 boxes: best \(bestExport); apply incl. backup: best \(bestImport)")
        #expect(bestExport <= .milliseconds(600), "export best of 5: \(bestExport)")
        #expect(bestImport <= .milliseconds(600), "apply best of 5: \(bestImport)")
    }
}

/// A transfer on a real database and backup folder in a temporary directory.
struct TransferHarness {
    static let app = ArchiveApp(name: "MediCabinet", platform: "ios", version: "0.5.0", build: "6")

    let directory: URL
    let database: AppDatabase
    let store: GRDBCabinetArchiveStore
    let backups: FileArchiveBackupStore
    let transfer: CabinetTransfer

    static func transfer(database: AppDatabase, backups: URL, at now: Date, app: ArchiveApp = app) -> CabinetTransfer {
        AppTransfer.make(database: database, backupsDirectory: backups, clock: FixedClock(now), app: app)
    }

    static func temporary(at now: Date, app: ArchiveApp = app) throws -> TransferHarness {
        let (database, directory) = try AppDatabase.temporary()
        let backupsDirectory = BackupService.defaultDirectory(in: directory)
        return TransferHarness(
            directory: directory, database: database, store: GRDBCabinetArchiveStore(database: database),
            backups: FileArchiveBackupStore(directory: backupsDirectory),
            transfer: transfer(database: database, backups: backupsDirectory, at: now, app: app)
        )
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: directory)
    }
}
