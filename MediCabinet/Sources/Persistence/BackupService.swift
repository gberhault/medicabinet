import Foundation
import GRDB
import MediCabinetCore

enum BackupError: Error {
    case cannotWrite(underlying: any Error)
}

/// Copies the cabinet database before pending migrations run, so an update can never cost
/// the user their data.
struct BackupService: Sendable {
    static let filePrefix = "pre-migration-"
    static let fileExtension = "sqlite"
    static let defaultKeepCount = 5

    /// The folder holding the backups (`Application Support/MediCabinet/Backups`).
    let directory: URL
    let clock: any Clock

    static func defaultDirectory(in appDirectory: URL) -> URL {
        appDirectory.appendingPathComponent("Backups", isDirectory: true)
    }

    /// Returns the backup URL, or `nil` when none is needed: the database is brand new
    /// (nothing to lose) or has no pending migration.
    func backupIfNeeded(database: DatabasePool, migrator: DatabaseMigrator, fileManager: FileManager = .default) throws -> URL? {
        let lastApplied: String? = try database.read { db in
            let applied = try migrator.appliedIdentifiers(db)
            let pending = try migrator.hasCompletedMigrations(db) == false
            guard pending, !applied.isEmpty else { return nil }
            return migrator.migrations.last { applied.contains($0) } ?? applied.sorted().last
        }
        guard let lastApplied else { return nil }

        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            try AppDatabase.protect(directory, fileManager: fileManager)
            let url = uniqueURL(lastApplied: lastApplied, fileManager: fileManager)
            try copy(database, to: url)
            try removeSideFiles(of: url, fileManager: fileManager)
            try AppDatabase.protect(url, fileManager: fileManager)
            return url
        } catch {
            throw BackupError.cannotWrite(underlying: error)
        }
    }

    /// Deletes all but the `count` newest pre-migration backups (ordered by the timestamp in
    /// their file name, so no file-date API is needed).
    func prune(keeping count: Int = BackupService.defaultKeepCount, fileManager: FileManager = .default) throws {
        guard fileManager.fileExists(atPath: directory.path) else { return }
        let backups = try fileManager.contentsOfDirectory(atPath: directory.path)
            .filter { $0.hasPrefix(Self.filePrefix) && $0.hasSuffix("." + Self.fileExtension) }
            .sorted()
        for name in backups.dropLast(max(count, 0)) {
            try fileManager.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    private func copy(_ database: DatabasePool, to url: URL) throws {
        let destination = try DatabaseQueue(path: url.path)
        try database.backup(to: destination)
        // A single self-contained file: no -wal / -shm side files to keep together.
        try destination.writeWithoutTransaction { db in
            try db.execute(sql: "PRAGMA journal_mode = DELETE")
        }
        try destination.close()
    }

    /// SQLite can leave an empty `-shm` (and `-wal`) behind after the switch out of WAL mode.
    /// A `-wal` that still holds data is never removed.
    private func removeSideFiles(of url: URL, fileManager: FileManager) throws {
        for suffix in ["-shm", "-wal"] {
            let side = URL(fileURLWithPath: url.path + suffix)
            guard fileManager.fileExists(atPath: side.path) else { continue }
            let size = (try? fileManager.attributesOfItem(atPath: side.path)[.size] as? Int) ?? nil
            if suffix == "-shm" || size == 0 { try fileManager.removeItem(at: side) }
        }
    }

    private func uniqueURL(lastApplied: String, fileManager: FileManager) -> URL {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let base = "\(Self.filePrefix)\(formatter.string(from: clock.now))-\(lastApplied)"
        var candidate = directory.appendingPathComponent("\(base).\(Self.fileExtension)")
        var counter = 2
        while fileManager.fileExists(atPath: candidate.path) {
            candidate = directory.appendingPathComponent("\(base)-\(counter).\(Self.fileExtension)")
            counter += 1
        }
        return candidate
    }
}
