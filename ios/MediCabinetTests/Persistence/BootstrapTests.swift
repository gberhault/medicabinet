import Foundation
import GRDB
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@Suite struct BootstrapTests {
    let root: URL
    let clock = FixedClock(Date(timeIntervalSince1970: 1_800_000_000))

    init() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("BootstrapTests-\(UUID().uuidString)", isDirectory: true)
    }

    private func bootstrap(_ migrator: DatabaseMigrator = Migrations.migrator) -> Bootstrap {
        Bootstrap(directory: root, migrator: migrator, clock: clock, defaultLocationName: "Armoire")
    }

    private func migrator(adding extra: [(String, @Sendable (Database) throws -> Void)]) -> DatabaseMigrator {
        var migrator = Migrations.migrator
        for (name, body) in extra { migrator.registerMigration(name, migrate: body) }
        return migrator
    }

    private struct Boom: Error {}

    private func environment(_ state: BootstrapState) throws -> AppEnvironment {
        guard case .ready(let environment) = state else { throw Failure("expected ready, got \(state)") }
        return environment
    }

    private struct Failure: Error { init(_ message: String) { self.message = message }; let message: String }

    private func openRaw() throws -> DatabasePool {
        try DatabasePool(path: root.appendingPathComponent(AppDatabase.fileName).path)
    }

    private func tableNames() throws -> Set<String> {
        let pool = try openRaw()
        defer { try? pool.close() }
        return try pool.read { Set(try String.fetchAll($0, sql: "SELECT name FROM sqlite_master WHERE type = 'table'")) }
    }

    private func backupFiles() -> [String] {
        let dir = BackupService.defaultDirectory(in: root)
        return ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).sorted()
    }

    // MARK: transfer (design §6)

    private struct StubInfo: AppInfo {
        let version = "9.8.7"
        let build = "42"
    }

    @Test func theEnvironmentCarriesTheTransferWithThisAppAsAuthor() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        var launch = bootstrap()
        launch.appInfo = StubInfo()
        let environment = try environment(await launch.run())
        let transfer = try #require(environment.transfer)

        let archive = try ArchiveDecoder.decode(try await transfer.exportArchive())
        #expect(archive.app == ArchiveApp(name: "MediCabinet", platform: "ios", version: "9.8.7", build: "42"))
        #expect(archive.exportedAt == clock.now)
        #expect(archive.cabinet.summary.locations == 1)
        #expect(try await transfer.backups().isEmpty)
        #expect(await environment.revision.value == 0)
    }

    // MARK: first launch and upgrade

    @Test func firstLaunchMigratesCreatesTheDefaultLocationAndTakesNoBackup() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let environment = try environment(await bootstrap().run())
        let all = try await environment.locations.all()
        #expect(all.count == 1)
        #expect(all.first?.isDefault == true)
        #expect(all.first?.name == "Armoire")
        #expect(backupFiles().isEmpty)
    }

    @Test func relaunchingKeepsASingleDefaultLocation() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        _ = try environment(await bootstrap().run())
        let again = try environment(await bootstrap().run())
        #expect(try await again.locations.all().count == 1)
        #expect(backupFiles().isEmpty)
    }

    @Test func anUpgradeBacksUpThenMigratesAndKeepsTheData() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let first = try environment(await bootstrap().run())
        let kept = Location(name: "Kitchen", createdAt: clock.now)
        try await first.locations.insert(kept)

        let withV5 = migrator(adding: [("v5", { db in try db.execute(sql: "CREATE TABLE later (id INTEGER)") })])
        let second = try environment(await bootstrap(withV5).run())

        #expect(backupFiles().count == 1)
        #expect(try await second.locations.all().map(\.name).sorted() == ["Armoire", "Kitchen"])
        #expect(try tableNames().contains("later"))
    }

    @Test func oldBackupsArePrunedAfterASuccessfulUpgrade() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        _ = try environment(await bootstrap().run())
        let dir = BackupService.defaultDirectory(in: root)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for day in 1...6 { try Data().write(to: dir.appendingPathComponent("pre-migration-2026010\(day)-080000-v1.sqlite")) }

        let withV5 = migrator(adding: [("v5", { db in try db.execute(sql: "CREATE TABLE later (id INTEGER)") })])
        _ = try environment(await bootstrap(withV5).run())

        #expect(backupFiles().count == 5)
        #expect(backupFiles().contains { $0.hasPrefix("pre-migration-20270115") })
    }

    // MARK: failures

    @Test func aFailingMigrationRestoresTheDatabaseToItsPreLaunchState() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let first = try environment(await bootstrap().run())
        try await first.locations.insert(Location(name: "Kitchen", createdAt: clock.now))

        // v5 commits, v6 fails: without a restore the database would be left half-upgraded.
        let broken = migrator(adding: [
            ("v5", { db in try db.execute(sql: "CREATE TABLE later (id INTEGER)") }),
            ("v6", { _ in throw Boom() })
        ])
        let state = await bootstrap(broken).run()

        guard case .failed(let failure) = state else { throw Failure("expected failure") }
        #expect(failure == .migration)
        #expect(!(try tableNames().contains("later")))
        let pool = try openRaw()
        defer { try? pool.close() }
        #expect(try await pool.read { try Migrations.migrator.appliedIdentifiers($0) } == Set(Migrations.identifiers))
        #expect(try await pool.read { try String.fetchAll($0, sql: "SELECT name FROM location ORDER BY name") } == ["Armoire", "Kitchen"])
        #expect(backupFiles().count == 1)
    }

    @Test func retryingAfterAFailedMigrationSucceedsOnceFixed() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        _ = try environment(await bootstrap().run())
        let broken = migrator(adding: [("v5", { _ in throw Boom() })])
        guard case .failed = await bootstrap(broken).run() else { throw Failure("expected failure") }

        let fixed = migrator(adding: [("v5", { db in try db.execute(sql: "CREATE TABLE later (id INTEGER)") })])
        let environment = try environment(await bootstrap(fixed).run())
        #expect(try await environment.locations.all().count == 1)
        #expect(try tableNames().contains("later"))
    }

    @Test func aFailedFirstLaunchLeavesAnEmptyDatabaseThatRetryMigrates() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        var broken = DatabaseMigrator()
        broken.registerMigration(Migrations.v1) { _ in throw Boom() }
        guard case .failed(let failure) = await bootstrap(broken).run() else { throw Failure("expected failure") }
        #expect(failure == .migration)
        #expect(backupFiles().isEmpty)
        #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent(AppDatabase.fileName).path))

        let environment = try environment(await bootstrap().run())
        #expect(try await environment.locations.all().count == 1)
    }

    @Test func anUnwritableBackupFolderStopsBeforeAnyMigration() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        _ = try environment(await bootstrap().run())
        try Data("x".utf8).write(to: BackupService.defaultDirectory(in: root))   // a file blocks the folder

        let withV5 = migrator(adding: [("v5", { db in try db.execute(sql: "CREATE TABLE later (id INTEGER)") })])
        guard case .failed(let failure) = await bootstrap(withV5).run() else { throw Failure("expected failure") }

        #expect(failure == .backup)
        #expect(!(try tableNames().contains("later")))
    }

    @Test func anUnusableDirectoryIsReportedAsAStorageFailure() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try Data("x".utf8).write(to: root)   // a file where the directory should be
        guard case .failed(let failure) = await bootstrap().run() else { throw Failure("expected failure") }
        #expect(failure == .storage)
    }

    @Test func anUnreadableDatabaseFileIsReportedAndNeverDeleted() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let file = root.appendingPathComponent(AppDatabase.fileName)
        let garbage = Data((0..<4096).map { UInt8(truncatingIfNeeded: $0 &* 31) })
        try garbage.write(to: file)

        guard case .failed(let failure) = await bootstrap().run() else { throw Failure("expected failure") }

        #expect(failure == .storage)
        #expect(try Data(contentsOf: file) == garbage, "the unreadable database must be left untouched")
    }

    // MARK: threading

    @Test @MainActor func bootstrapWorkRunsOffTheMainThread() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let probe = ThreadProbe()
        let probing = migrator(adding: [("v5", { _ in probe.record(isMain: Thread.isMainThread) })])
        _ = try environment(await bootstrap(probing).run())
        #expect(probe.sawMigration)
        #expect(!probe.ranOnMain)
    }

    // MARK: the named products fill (#57)

    @Test func theFillRunsOnceFromTheBootstrappedCabinetAndClearsItsMark() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let reference = root.appendingPathComponent("reference.sqlite")
        try FileManager.default.copyItem(at: SharedFixtures.referenceSampleURL, to: reference)
        var launch = bootstrap()
        launch.referenceURL = reference
        let environment = try environment(await launch.run())
        let location = try #require(try await environment.locations.defaultLocation())
        for (gtin, name) in [("03400900000013", "Testamol"), ("03401900000003", "Arnica")] {
            try await environment.boxes.insert(Box(
                product: ProductRef(gtin: gtin, name: name), locationID: location.id, createdAt: clock.now
            ))
        }
        #expect(try await environment.named.backfillPending())

        #expect(await environment.namedBackfill.run() == .completed(created: 1))
        #expect(try await environment.named.namedProduct(gtin: "03401900000003")?.name == "Arnica")
        #expect(try await environment.named.namedProduct(gtin: "03400900000013") == nil, "the reference names this one")
        #expect(await environment.namedBackfill.run() == .notPending)
    }

    @Test func theFillWaitsWhenTheReferenceDataIsMissing() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let environment = try environment(await bootstrap().run())
        #expect(await environment.namedBackfill.run() == .referenceUnavailable)
        #expect(try await environment.named.backfillPending())
    }

    @Test func startingTheFillAtLaunchRunsInTheBackground() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let environment = try environment(await bootstrap().run())
        await AppLaunch.startBackfill(environment).value
        #expect(try await environment.named.backfillPending(), "no reference data in this launch: still pending")
    }
}

private final class ThreadProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var observations: [Bool] = []

    func record(isMain: Bool) {
        lock.lock()
        defer { lock.unlock() }
        observations.append(isMain)
    }

    var sawMigration: Bool { lock.withLock { !observations.isEmpty } }
    var ranOnMain: Bool { lock.withLock { observations.contains(true) } }
}
