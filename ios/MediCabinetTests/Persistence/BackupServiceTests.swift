import Foundation
import GRDB
import MediCabinetCore
import Testing
@testable import MediCabinet

@Suite struct BackupServiceTests {
    let root: URL
    let clock = FixedClock(Date(timeIntervalSince1970: 1_800_000_000))   // 2027-01-15 08:00:00 UTC

    init() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("BackupServiceTests-\(UUID().uuidString)", isDirectory: true)
    }

    private var service: BackupService {
        BackupService(directory: BackupService.defaultDirectory(in: root), clock: clock)
    }

    private var migratorWithPendingV3: DatabaseMigrator {
        var migrator = Migrations.migrator
        migrator.registerMigration("v3") { db in try db.execute(sql: "CREATE TABLE later (id INTEGER)") }
        return migrator
    }

    /// An on-disk database migrated to the current schema and holding one location.
    private func existingDatabase() throws -> DatabasePool {
        let pool = try AppDatabase.openPool(in: root)
        try Migrations.migrator.migrate(pool)
        try pool.write { db in
            try db.execute(
                sql: "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) VALUES ('L', 'Kept', 0, 0, ?, ?)",
                arguments: [Date(), Date()]
            )
        }
        return pool
    }

    private func backupFiles() throws -> [String] {
        let dir = BackupService.defaultDirectory(in: root)
        return ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).sorted()
    }

    @Test func aBrandNewDatabaseNeedsNoBackup() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let pool = try AppDatabase.openPool(in: root)
        #expect(try service.backupIfNeeded(database: pool, migrator: Migrations.migrator) == nil)
        #expect(try backupFiles().isEmpty)
    }

    @Test func aFullyMigratedDatabaseNeedsNoBackup() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let pool = try existingDatabase()
        #expect(try service.backupIfNeeded(database: pool, migrator: Migrations.migrator) == nil)
        #expect(try backupFiles().isEmpty)
    }

    @Test func aPendingMigrationOnAnExistingDatabaseIsBackedUpFirst() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let pool = try existingDatabase()
        let url = try #require(try service.backupIfNeeded(database: pool, migrator: migratorWithPendingV3))

        #expect(url.lastPathComponent == "pre-migration-20270115-080000-v2.sqlite")
        let backup = try DatabaseQueue(path: url.path)
        #expect(try backup.read { try String.fetchOne($0, sql: "SELECT name FROM location") } == "Kept")
        #expect(try backup.read { try Migrations.migrator.appliedIdentifiers($0) } == [Migrations.v1, Migrations.schemaV2])
        // Self-contained: no WAL side files next to the backup.
        let files = try backupFiles()
        #expect(files == [url.lastPathComponent], "found: \(files)")
    }

    @Test func twoBackupsInTheSameSecondDoNotOverwriteEachOther() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let pool = try existingDatabase()
        let first = try #require(try service.backupIfNeeded(database: pool, migrator: migratorWithPendingV3))
        let second = try #require(try service.backupIfNeeded(database: pool, migrator: migratorWithPendingV3))
        #expect(first != second)
        #expect(try backupFiles().count == 2)
    }

    @Test func anUnwritableBackupFolderFailsInsteadOfSkippingTheBackup() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let pool = try existingDatabase()
        // A regular file where the Backups folder should be.
        try Data("x".utf8).write(to: BackupService.defaultDirectory(in: root))
        #expect(throws: BackupError.self) {
            try service.backupIfNeeded(database: pool, migrator: migratorWithPendingV3)
        }
    }

    @Test func pruningKeepsTheNewestFive() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let dir = BackupService.defaultDirectory(in: root)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let names = (1...7).map { "pre-migration-2027010\($0)-080000-v2.sqlite" }
        for name in names { try Data().write(to: dir.appendingPathComponent(name)) }
        try Data().write(to: dir.appendingPathComponent("notes.txt"))

        try service.prune(keeping: 5)

        #expect(try backupFiles() == ["notes.txt"] + names.suffix(5))
    }

    @Test func pruningWithFewerThanTheLimitKeepsEverythingAndNoFolderIsFine() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try service.prune()   // folder does not exist yet
        let dir = BackupService.defaultDirectory(in: root)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data().write(to: dir.appendingPathComponent("pre-migration-20270101-080000-v2.sqlite"))
        try service.prune()
        #expect(try backupFiles().count == 1)
    }
}
