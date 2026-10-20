import Foundation
import MediCabinetCore
import Testing
import UniformTypeIdentifiers
@testable import MediCabinet

/// Where imports start, and when they may (O1, O4, O5, B5).
@MainActor
@Suite struct ImportCoordinatorTests {
    private let root: URL
    private let files: ArchiveFiles

    init() {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("ImportCoordinatorTests-\(UUID().uuidString)", isDirectory: true)
        files = ArchiveFiles(
            exportDirectory: root.appendingPathComponent("tmp/Export", isDirectory: true),
            inboxDirectory: root.appendingPathComponent("Documents/Inbox", isDirectory: true)
        )
    }

    private func openedCopy(_ name: String = "cabinet.medicabinet") throws -> URL {
        let inbox = try #require(files.inboxDirectory)
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let url = inbox.appendingPathComponent(name)
        try Data("{}".utf8).write(to: url)
        return url
    }

    private func readyCoordinator() -> ImportCoordinator {
        let coordinator = ImportCoordinator(files: files)
        coordinator.cabinetOpened()
        return coordinator
    }

    /// The copy is deleted off the main actor: wait for it, a little.
    private func eventuallyGone(_ url: URL) async -> Bool {
        for _ in 0..<200 {
            if !FileManager.default.fileExists(atPath: url.path) { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return false
    }

    private let backup = ArchiveBackupEntry(
        id: "pre-import-20270115-080000-000.medicabinet", takenAt: Date(timeIntervalSince1970: 1_800_000_000),
        reason: .beforeImport, summary: nil
    )

    @Test func aFileOpenedWhileTheCabinetIsLoadingWaitsForIt() throws {
        let coordinator = ImportCoordinator(files: files)
        let url = root.appendingPathComponent("a.medicabinet")
        coordinator.receive(url)
        #expect(coordinator.active == nil)
        #expect(coordinator.heldFiles == [url])

        coordinator.cabinetOpened()

        #expect(coordinator.active?.source == .file(url))
        #expect(coordinator.active?.reason == .beforeImport)
        #expect(coordinator.waiting.isEmpty)
    }

    @Test func aFileOpenedWhenTheCabinetCannotBeOpenedIsRefusedAndItsCopyDeleted() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let coordinator = ImportCoordinator(files: files)
        coordinator.cabinetUnavailable()
        let copy = try openedCopy()

        coordinator.receive(copy)

        #expect(coordinator.active == nil)
        #expect(coordinator.showsUnavailable)
        #expect(await eventuallyGone(copy))
    }

    @Test func filesWaitingWhenLaunchFailsAreRefused() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let coordinator = ImportCoordinator(files: files)
        let copy = try openedCopy()
        coordinator.receive(copy)

        coordinator.cabinetUnavailable()

        #expect(coordinator.waiting.isEmpty)
        #expect(coordinator.showsUnavailable)
        #expect(await eventuallyGone(copy))
    }

    @Test func aFileWaitsWhileAFormHoldsUnsavedInputAndOpensWhenItCloses() {
        let coordinator = readyCoordinator()
        let form = UUID()
        coordinator.beginBusy(form)
        coordinator.receive(root.appendingPathComponent("a.medicabinet"))

        #expect(coordinator.active == nil)
        #expect(coordinator.hasWaitingFile)

        coordinator.endBusy(form)

        #expect(coordinator.active != nil)
        #expect(!coordinator.hasWaitingFile)
    }

    @Test func everyFormMustCloseFirst() {
        let coordinator = readyCoordinator()
        let (first, second) = (UUID(), UUID())
        coordinator.beginBusy(first)
        coordinator.beginBusy(second)
        coordinator.receive(root.appendingPathComponent("a.medicabinet"))
        coordinator.endBusy(first)
        #expect(coordinator.active == nil)
        coordinator.endBusy(second)
        #expect(coordinator.active != nil)
    }

    @Test func oneFlowAtATimeTheNextOpensWhenTheFirstEnds() {
        let coordinator = readyCoordinator()
        let first = root.appendingPathComponent("first.medicabinet")
        let second = root.appendingPathComponent("second.medicabinet")
        coordinator.receive(first)
        coordinator.open(second)
        #expect(coordinator.active?.source == .file(first))
        #expect(coordinator.heldFiles == [first, second])

        coordinator.finish()

        #expect(coordinator.active?.source == .file(second))
        coordinator.finish()
        #expect(coordinator.active == nil)
        coordinator.finish()
        #expect(coordinator.active == nil)
    }

    @Test func theEndOfAFlowDeletesTheOpenedCopyButNeverAPickedFile() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let coordinator = readyCoordinator()
        let copy = try openedCopy()
        coordinator.receive(copy)
        coordinator.finish()
        #expect(await eventuallyGone(copy))

        let folder = root.appendingPathComponent("Picked", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let picked = folder.appendingPathComponent("picked.medicabinet")
        try Data("{}".utf8).write(to: picked)
        coordinator.open(picked)
        coordinator.finish()
        try await Task.sleep(for: .milliseconds(100))
        #expect(FileManager.default.fileExists(atPath: picked.path))
    }

    @Test func aRestoreIsAFlowBackedUpAsBeforeARestore() {
        let coordinator = readyCoordinator()
        coordinator.restore(backup)
        #expect(coordinator.active?.source == .backup(backup))
        #expect(coordinator.active?.reason == .beforeRestore)
        #expect(coordinator.heldFiles.isEmpty)
    }

    @Test func onlyFileURLsAreReceived() throws {
        let coordinator = readyCoordinator()
        coordinator.receive(try #require(URL(string: "https://example.com/a.medicabinet")))
        #expect(coordinator.active == nil)
        #expect(coordinator.waiting.isEmpty)
    }
}

/// The document type declared to iOS (O1, design §1.4).
@Suite struct ArchiveDocumentTypeTests {
    private let info = Bundle.main.infoDictionary ?? [:]

    @Test func theArchiveTypeIsExportedAsDataWithItsExtensionAndMIMEType() throws {
        let declarations = try #require(info["UTExportedTypeDeclarations"] as? [[String: Any]])
        let archive = try #require(declarations.first { $0["UTTypeIdentifier"] as? String == "com.medicabinet.archive" })
        #expect(archive["UTTypeConformsTo"] as? [String] == ["public.data"])
        let tags = try #require(archive["UTTypeTagSpecification"] as? [String: Any])
        #expect(tags["public.filename-extension"] as? [String] == [ArchiveFormat.fileExtension])
        #expect(tags["public.mime-type"] as? [String] == [ArchiveFormat.mimeType])
    }

    @Test func theAppOpensArchivesAsACopyAndClaimsNothingElse() throws {
        #expect(info["LSSupportsOpeningDocumentsInPlace"] as? Bool == false)
        let types = try #require(info["CFBundleDocumentTypes"] as? [[String: Any]])
        #expect(types.count == 1)
        #expect(types.first?["LSItemContentTypes"] as? [String] == ["com.medicabinet.archive"])
        #expect(types.first?["CFBundleTypeRole"] as? String == "Viewer")
        #expect(types.first?["LSHandlerRank"] as? String == "Owner")
    }

    @Test func theTypeIsKnownByItsExtensionAndIsNotJSON() {
        #expect(UTType.medicabinetArchive.identifier == "com.medicabinet.archive")
        #expect(UTType(filenameExtension: "medicabinet") == .medicabinetArchive)
        #expect(UTType.medicabinetArchive.conforms(to: .data))
        #expect(!UTType.medicabinetArchive.conforms(to: .json))
    }

    @Test func theUITestLaunchArgumentCopiesTheFileIntoTheInbox() async throws {
        let source = FileManager.default.temporaryDirectory.appendingPathComponent("opened-\(UUID().uuidString).medicabinet")
        try Data("{}".utf8).write(to: source)
        defer { try? FileManager.default.removeItem(at: source) }

        let copy = try #require(await AppLaunch.uiTestOpenedArchive(
            arguments: [AppLaunch.temporaryStorageArgument, AppLaunch.openArchiveArgument, source.path]))
        defer { try? FileManager.default.removeItem(at: copy) }

        #expect(ArchiveFiles.live.isInInbox(copy))
        #expect(try Data(contentsOf: copy) == Data("{}".utf8))
        #expect(await AppLaunch.uiTestOpenedArchive(arguments: [AppLaunch.openArchiveArgument, source.path]) == nil)
    }
}
