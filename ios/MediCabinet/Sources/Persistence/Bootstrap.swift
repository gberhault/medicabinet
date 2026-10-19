import Foundation
import GRDB
import MediCabinetCore

enum BootstrapFailure: Error, Equatable, Sendable {
    /// The database could not be opened or written (e.g. no disk space).
    case storage
    /// The pre-migration backup could not be written; nothing was migrated.
    case backup
    /// A migration failed; the pre-migration backup was put back.
    case migration
}

enum BootstrapState: Sendable {
    case ready(AppEnvironment)
    case failed(BootstrapFailure)
}

/// Opens the cabinet, backs it up when migrations are pending, migrates, makes sure a default
/// location exists. Never deletes the database; a failure leaves the user's data as it was.
struct Bootstrap: Sendable {
    /// `Application Support/MediCabinet` (the database file and its `Backups` folder live here).
    var directory: URL
    var migrator: DatabaseMigrator = Migrations.migrator
    var clock: any Clock = SystemClock()
    var defaultLocationName: String
    var backupsToKeep = BackupService.defaultKeepCount
    /// The bundled reference database (`nil` = not bundled). Only stored here: it is opened on first use, not at launch.
    var referenceURL: URL?

    /// Runs off the main actor.
    func run() async -> BootstrapState {
        await Task.detached(priority: .userInitiated) { await execute() }.value
    }

    private func execute() async -> BootstrapState {
        let pool: DatabasePool
        do {
            pool = try AppDatabase.openPool(in: directory)
        } catch {
            Log.bootstrap.error("Cannot open the database: \(error.localizedDescription)")
            return .failed(.storage)
        }

        let backups = BackupService(directory: BackupService.defaultDirectory(in: directory), clock: clock)
        let backupURL: URL?
        do {
            backupURL = try backups.backupIfNeeded(database: pool, migrator: migrator)
        } catch {
            Log.bootstrap.error("Pre-migration backup failed, migration skipped: \(error.localizedDescription)")
            return .failed(.backup)
        }

        let database: AppDatabase
        do {
            database = try AppDatabase.migrate(pool, using: migrator)
        } catch {
            Log.bootstrap.error("Migration failed, restoring the pre-migration copy: \(error.localizedDescription)")
            if let backupURL { restore(backupURL, over: pool) }
            return .failed(.migration)
        }

        let locations = GRDBLocationRepository(database: database, clock: clock)
        do {
            _ = try await locations.ensureDefault(named: defaultLocationName)
        } catch {
            Log.bootstrap.error("Cannot create the default location: \(error.localizedDescription)")
            return .failed(.storage)
        }
        try? backups.prune(keeping: backupsToKeep)

        return .ready(AppEnvironment(
            locations: locations,
            boxes: GRDBBoxRepository(database: database, clock: clock),
            clock: clock,
            reference: GRDBReferenceData(url: referenceURL)
        ))
    }

    /// Puts the pre-migration copy back so the database is exactly as it was before this launch.
    /// The backup file stays in `Backups/` either way, so the data is never lost.
    private func restore(_ backup: URL, over pool: DatabasePool, fileManager: FileManager = .default) {
        do {
            try pool.close()
            let database = directory.appendingPathComponent(AppDatabase.fileName)
            for suffix in ["-wal", "-shm"] {
                let side = URL(fileURLWithPath: database.path + suffix)
                if fileManager.fileExists(atPath: side.path) { try fileManager.removeItem(at: side) }
            }
            try fileManager.removeItem(at: database)
            try fileManager.copyItem(at: backup, to: database)
            try AppDatabase.protect(database, fileManager: fileManager)
        } catch {
            // Leave everything in place; the backup remains available for a manual restore.
            Log.bootstrap.fault("Restoring the pre-migration copy failed: \(error.localizedDescription)")
        }
    }
}
