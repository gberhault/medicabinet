import Foundation
import GRDB
import Testing
@testable import MediCabinet

@Suite struct AppDatabaseTests {
    @Test func temporaryDatabaseIsCreatedOnDiskAndMigrated() throws {
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        #expect(FileManager.default.fileExists(atPath: directory.appendingPathComponent(AppDatabase.fileName).path))
        #expect(try database.writer.read { try Migrations.migrator.appliedIdentifiers($0) } == [Migrations.v1])
    }

    /// The simulator does not expose Data Protection (the attribute reads back nil), so the
    /// request itself is verified through a recording FileManager.
    @Test func directoryAndDatabaseFilesAreProtected() throws {
        let recorder = RecordingFileManager()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("MediCabinetTests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        _ = try AppDatabase.openPool(in: directory, fileManager: recorder)

        let protectedPaths = Set(recorder.protectedPaths)
        #expect(protectedPaths.contains(directory.path))
        #expect(protectedPaths.contains(directory.appendingPathComponent(AppDatabase.fileName).path))
        #expect(recorder.protections.allSatisfy { $0 == AppDatabase.fileProtection })
        #expect(AppDatabase.fileProtection == .completeUntilFirstUserAuthentication)
    }

    #if !targetEnvironment(simulator)
    @Test func protectionIsReadableBackOnDevice() throws {
        let (_, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        let attributes = try FileManager.default.attributesOfItem(atPath: directory.path)
        #expect(attributes[.protectionKey] as? FileProtectionType == AppDatabase.fileProtection)
    }
    #endif

    @Test func reopeningAnExistingDatabaseKeepsItsData() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("MediCabinetTests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        do {
            let database = try AppDatabase.migrate(AppDatabase.openPool(in: directory))
            try database.writer.write { db in
                try db.execute(
                    sql: "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) VALUES ('L', 'Kept', 0, 0, ?, ?)",
                    arguments: [Date(), Date()]
                )
            }
        }
        let reopened = try AppDatabase.migrate(AppDatabase.openPool(in: directory))
        #expect(try reopened.writer.read { try String.fetchOne($0, sql: "SELECT name FROM location") } == "Kept")
    }

    @Test func defaultDirectoryIsInsideApplicationSupport() throws {
        let directory = try AppDatabase.defaultDirectory()
        #expect(directory.lastPathComponent == "MediCabinet")
        #expect(directory.deletingLastPathComponent().lastPathComponent == "Application Support")
    }
}

private final class RecordingFileManager: FileManager, @unchecked Sendable {
    private(set) var protectedPaths: [String] = []
    private(set) var protections: [FileProtectionType] = []

    override func setAttributes(_ attributes: [FileAttributeKey: Any], ofItemAtPath path: String) throws {
        if let protection = attributes[.protectionKey] as? FileProtectionType {
            protectedPaths.append(path)
            protections.append(protection)
        }
        // Real attribute changes are a no-op on the simulator; nothing to forward.
    }
}
