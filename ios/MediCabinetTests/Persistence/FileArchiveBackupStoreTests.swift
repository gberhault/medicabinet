import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@Suite struct FileArchiveBackupStoreTests {
    private let root: URL
    private let archive = Array(SyntheticArchive.json(locations: 2, boxes: 3).utf8)
    /// 2027-01-15 08:00:00.123 UTC.
    private let instant = Date(timeIntervalSince1970: 1_800_000_000.123)

    init() {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("FileArchiveBackupStoreTests-\(UUID().uuidString)", isDirectory: true)
    }

    private var directory: URL { BackupService.defaultDirectory(in: root) }
    private var store: FileArchiveBackupStore { FileArchiveBackupStore(directory: directory) }

    private func files() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).sorted()
    }

    // MARK: Names

    @Test func namesCarryTheReasonAndTheUTCInstantToTheMillisecond() {
        #expect(FileArchiveBackupStore.fileName(reason: .beforeImport, takenAt: instant) == "pre-import-20270115-080000-123.medicabinet")
        #expect(FileArchiveBackupStore.fileName(reason: .beforeRestore, takenAt: instant, counter: 2)
            == "pre-restore-20270115-080000-123-2.medicabinet")
    }

    @Test func aNameReadsBackAsItsReasonAndInstant() throws {
        let parsed = try #require(FileArchiveBackupStore.parse("pre-restore-20270115-080000-123-2.medicabinet"))
        #expect(parsed.reason == .beforeRestore)
        #expect(abs(parsed.takenAt.timeIntervalSince(instant)) < 0.000_5)
    }

    @Test(arguments: [
        "pre-migration-20270115-080000-v2.sqlite", "pre-import-20270115-080000-123.medicabinet.partial",
        "pre-import-2027011-080000-123.medicabinet", "pre-import-20271315-080000-123.medicabinet",
        "pre-import-20270115-080000-12x.medicabinet", "pre-import-20270115-080000.medicabinet", "notes.txt",
        "pre-import-20270115-080000-123-2-3.medicabinet", "pre-import-٢٠٢٧٠١١٥-080000-123.medicabinet"
    ])
    func otherFilesAreNotBackups(name: String) {
        #expect(FileArchiveBackupStore.parse(name) == nil)
    }

    @Test func theBackupFilesUseTheDatabaseProtectionClass() {
        #expect(FileArchiveBackupStore.fileProtection == AppDatabase.fileProtection)
    }

    // MARK: Write

    @Test func aWrittenBackupIsCompleteListedAndReadsBack() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let entry = try store.write(archive, reason: .beforeImport, takenAt: instant)

        #expect(entry.id == "pre-import-20270115-080000-123.medicabinet")
        #expect(entry.summary == CabinetSummary(locations: 2, inCabinet: 3, removed: 0, deleted: 0, unknownExpiry: 0))
        #expect(files() == [entry.id])
        #expect(try await store.read(entry.id) == archive)
        let listed = try await store.list()
        #expect(listed.map(\.id) == [entry.id])
        #expect(listed.first?.summary == entry.summary)
        #expect(listed.first?.reason == .beforeImport)
    }

    @Test func somethingThatDoesNotReadBackAsAnArchiveLeavesNoFile() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        #expect(throws: (any Error).self) { try store.write([0x7B], reason: .beforeImport, takenAt: instant) }
        #expect(files().isEmpty)
    }

    @Test func aWriteThatCannotReachTheFolderThrows() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        // A file where the folder should be: like a full or read-only disk, the write fails.
        try Data().write(to: directory)
        #expect(throws: (any Error).self) { try store.write(archive, reason: .beforeImport, takenAt: instant) }
    }

    @Test func twoBackupsInTheSameMillisecondGetDistinctNames() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let first = try store.write(archive, reason: .beforeImport, takenAt: instant)
        let second = try store.write(archive, reason: .beforeImport, takenAt: instant)
        #expect(first.id != second.id)
        #expect(second.id == "pre-import-20270115-080000-123-2.medicabinet")
    }

    @Test func theFolderAndTheFileAreProtected() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let recorder = ProtectionRecorder()
        var store = store
        store.fileManager = recorder
        let entry = try store.write(archive, reason: .beforeImport, takenAt: instant)

        #expect(recorder.protectedPaths.contains(directory.path))
        #expect(recorder.protectedPaths.contains(directory.appendingPathComponent(entry.id + ".partial").path))
        #expect(recorder.protections.allSatisfy { $0 == AppDatabase.fileProtection })
    }

    // MARK: List and read

    @Test func anUnreadableBackupIsListedWithoutASummary() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let entry = try store.write(archive, reason: .beforeRestore, takenAt: instant)
        try Data("{".utf8).write(to: directory.appendingPathComponent(entry.id))

        let listed = try await store.list()
        #expect(listed.map(\.id) == [entry.id])
        #expect(listed.first?.summary == nil)
        #expect(listed.first?.reason == .beforeRestore)
    }

    @Test func noFolderMeansNoBackups() async throws {
        #expect(try await store.list().isEmpty)
        try await store.prune(keeping: 3)
    }

    @Test(arguments: ["../cabinet.sqlite", "pre-migration-20270115-080000-v2.sqlite", "pre-import-20270115-080000-123.medicabinet"])
    func onlyExistingBackupsCanBeRead(id: String) async throws {
        await #expect(throws: ArchiveBackupFileError.unknownBackup) { try await store.read(id) }
    }

    // MARK: Prune

    @Test func pruneKeepsTheNewestOverBothReasonsAndLeavesPreMigrationCopiesAlone() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let reasons: [BackupReason] = [.beforeImport, .beforeRestore, .beforeImport, .beforeRestore, .beforeImport]
        var written: [ArchiveBackupEntry] = []
        for (offset, reason) in reasons.enumerated() {
            written.append(try store.write(archive, reason: reason, takenAt: instant.addingTimeInterval(Double(offset) * 60)))
        }
        let migration = "pre-migration-20270115-075900-v2.sqlite"
        try Data("db".utf8).write(to: directory.appendingPathComponent(migration))
        let leftover = "pre-import-20270115-070000-000.medicabinet.partial"
        try Data("half".utf8).write(to: directory.appendingPathComponent(leftover))

        try await store.prune(keeping: 3)

        let newest = written.reversed().prefix(3).map(\.id)
        #expect(try await store.list().map(\.id) == Array(newest))
        #expect(files().contains(migration))
        #expect(!files().contains(leftover))
        #expect(files().count == 4)
    }
}

/// Records the protection classes set, instead of relying on the simulator, which does not keep them.
private final class ProtectionRecorder: FileManager, @unchecked Sendable {
    private(set) var protectedPaths: [String] = []
    private(set) var protections: [FileProtectionType] = []

    override func setAttributes(_ attributes: [FileAttributeKey: Any], ofItemAtPath path: String) throws {
        if let protection = attributes[.protectionKey] as? FileProtectionType {
            protectedPaths.append(path)
            protections.append(protection)
        }
        try super.setAttributes(attributes, ofItemAtPath: path)
    }
}
