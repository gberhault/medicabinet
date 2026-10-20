import Foundation
import Testing
@testable import MediCabinet

@Suite struct ArchiveFilesTests {
    private let root: URL
    private let files: ArchiveFiles

    init() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("ArchiveFilesTests-\(UUID().uuidString)", isDirectory: true)
        files = ArchiveFiles(
            exportDirectory: root.appendingPathComponent("tmp/Export", isDirectory: true),
            inboxDirectory: root.appendingPathComponent("Documents/Inbox", isDirectory: true)
        )
    }

    private func inboxFile(_ name: String, bytes: [UInt8] = [0x7B, 0x7D]) throws -> URL {
        let inbox = try #require(files.inboxDirectory)
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let url = inbox.appendingPathComponent(name)
        try Data(bytes).write(to: url)
        return url
    }

    private func exists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    // MARK: Export file

    @Test func theExportIsWrittenUnderItsNameAndReplacesAnEarlierOne() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let earlier = try files.writeExport([1], named: "MediCabinet-2026-10-19.medicabinet")
        let url = try files.writeExport([1, 2, 3], named: "MediCabinet-2026-10-20.medicabinet")

        #expect(url.lastPathComponent == "MediCabinet-2026-10-20.medicabinet")
        #expect(try Data(contentsOf: url) == Data([1, 2, 3]))
        #expect(!exists(earlier))
    }

    @Test func theExportFileIsDeletedAfterTheShare() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let url = try files.writeExport([1], named: "MediCabinet-2026-10-20.medicabinet")
        files.deleteExport(url)
        #expect(!exists(url))
    }

    @Test func onlyFilesOfTheExportFolderAreDeletedAsExports() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let elsewhere = try inboxFile("kept.medicabinet")
        files.deleteExport(elsewhere)
        #expect(exists(elsewhere))
    }

    // MARK: Inbox

    @Test func anOpenedCopyIsRecognisedAndDiscarded() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let copy = try inboxFile("cabinet.medicabinet")
        let picked = root.appendingPathComponent("Picked/cabinet.medicabinet")
        #expect(files.isInInbox(copy))
        #expect(!files.isInInbox(picked))
        files.discardIfInInbox(copy)
        #expect(!exists(copy))
    }

    @Test func aFilePickedElsewhereIsNeverDeleted() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let folder = root.appendingPathComponent("Picked", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let picked = folder.appendingPathComponent("cabinet.medicabinet")
        try Data([1]).write(to: picked)
        files.discardIfInInbox(picked)
        #expect(exists(picked))
    }

    @Test func withoutAnInboxNothingIsInIt() {
        let bare = ArchiveFiles(exportDirectory: root, inboxDirectory: nil)
        #expect(!bare.isInInbox(root.appendingPathComponent("a")))
        bare.cleanUp()
    }

    // MARK: Launch cleanup

    @Test func launchCleanupEmptiesTheExportFolderAndTheInboxButKeepsAWaitingFile() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let export = try files.writeExport([1], named: "MediCabinet-2026-10-20.medicabinet")
        let old = try inboxFile("old.medicabinet")
        let waiting = try inboxFile("waiting.medicabinet")

        files.cleanUp(keeping: [waiting])

        #expect(!exists(export))
        #expect(!exists(old))
        #expect(exists(waiting))
    }

    // MARK: Capped reading

    @Test func aFileWithinTheLimitIsRead() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let url = try inboxFile("small.medicabinet", bytes: [1, 2, 3, 4])
        #expect(try ArchiveFiles.readCapped(url, limit: 4) == [1, 2, 3, 4])
    }

    @Test func aFileOverTheLimitIsRefusedUnread() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let url = try inboxFile("big.medicabinet", bytes: [1, 2, 3, 4, 5])
        #expect(throws: ArchiveReadError.tooLarge) { try ArchiveFiles.readCapped(url, limit: 4) }
    }

    @Test func aMissingFileIsUnreadable() {
        #expect(throws: ArchiveReadError.unreadable) {
            try ArchiveFiles.readCapped(root.appendingPathComponent("missing.medicabinet"), limit: 4)
        }
    }
}
