import Foundation
import GRDB

/// The cabinet database. Opening and migrating are separate steps so `Bootstrap` can take a
/// backup between them.
struct AppDatabase: Sendable {
    static let fileName = "cabinet.sqlite"
    /// Readable after the first unlock since boot, so notification digests can be rescheduled
    /// while the phone is locked. Data is still encrypted at rest until that first unlock.
    static let fileProtection = FileProtectionType.completeUntilFirstUserAuthentication

    let writer: any DatabaseWriter

    /// Wraps a writer that is already migrated.
    init(migrated writer: any DatabaseWriter) {
        self.writer = writer
    }

    /// `Application Support/MediCabinet`.
    static func defaultDirectory(fileManager: FileManager = .default) throws -> URL {
        try fileManager
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("MediCabinet", isDirectory: true)
    }

    /// Creates the directory (protected) and opens the database file without migrating it.
    static func openPool(in directory: URL, fileManager: FileManager = .default) throws -> DatabasePool {
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        try protect(directory, fileManager: fileManager)
        let pool = try DatabasePool(path: directory.appendingPathComponent(fileName).path)
        for suffix in ["", "-wal", "-shm"] {
            let file = directory.appendingPathComponent(fileName + suffix)
            if fileManager.fileExists(atPath: file.path) { try protect(file, fileManager: fileManager) }
        }
        return pool
    }

    static func migrate(_ writer: any DatabaseWriter) throws -> AppDatabase {
        try Migrations.migrator.migrate(writer)
        return AppDatabase(migrated: writer)
    }

    static func inMemory() throws -> AppDatabase {
        try migrate(DatabaseQueue())
    }

    /// A migrated on-disk database in a fresh temporary directory (tests).
    static func temporary() throws -> (database: AppDatabase, directory: URL) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("MediCabinetTests-\(UUID().uuidString)", isDirectory: true)
        let pool = try openPool(in: directory)
        return (try migrate(pool), directory)
    }

    private static func protect(_ url: URL, fileManager: FileManager) throws {
        try fileManager.setAttributes([.protectionKey: fileProtection], ofItemAtPath: url.path)
    }
}
