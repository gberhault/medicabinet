import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

/// The export flow (E2–E9): summary and warning first, the file only after Continue, deleted when the share ends.
@MainActor
@Suite struct ExportViewModelTests {
    private func model(_ kit: TransferTestKit, transfer: CabinetTransfer? = nil, recorder: AnnouncementRecorder = AnnouncementRecorder())
        -> ExportViewModel {
        ExportViewModel(
            transfer: transfer ?? kit.transfer, files: kit.files, clock: kit.clock, timeZone: { .gmt },
            announce: { recorder.record($0) }
        )
    }

    private func sharedURL(_ model: ExportViewModel) throws -> URL {
        guard case let .sharing(url) = model.state else { throw TestFailure("expected sharing, got \(model.state)") }
        return url
    }

    private struct TestFailure: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }

    @Test func theSummaryComesFirstAndNothingIsWrittenYet() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        let removed = try await kit.addBox("Smecta")
        try await kit.boxes.markRemoved(id: removed.id, reason: .usedUp, at: kit.clock.now)
        let model = model(kit)

        await model.start()

        #expect(model.state == .confirm(CabinetSummary(locations: 1, inCabinet: 1, removed: 1, deleted: 0, unknownExpiry: 1)))
        #expect(kit.exportFiles().isEmpty)
    }

    @Test func cancelExportsNothing() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let model = model(kit)
        await model.start()
        model.cancel()
        #expect(model.state == .idle)
        #expect(kit.exportFiles().isEmpty)
    }

    @Test func continueWritesTheWholeCabinetUnderTheDatedName() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        let model = model(kit)
        await model.start()

        await model.confirm()

        let url = try sharedURL(model)
        #expect(url.lastPathComponent == "MediCabinet-2027-01-15.medicabinet")
        let archive = try ArchiveDecoder.decode([UInt8](try Data(contentsOf: url)))
        #expect(archive.cabinet.summary.inCabinet == 1)
        #expect(archive.app.platform == "ios")
        #expect(archive.exportedAt == kit.clock.now)
    }

    @Test func theTemporaryFileIsDeletedWhenTheShareSheetIsDismissed() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let recorder = AnnouncementRecorder()
        let model = model(kit, recorder: recorder)
        await model.start()
        await model.confirm()
        let url = try sharedURL(model)

        await model.shareFinished(activity: nil, completed: false)

        #expect(model.state == .idle)
        #expect(!FileManager.default.fileExists(atPath: url.path))
        #expect(recorder.messages.isEmpty)
    }

    @Test func savingToFilesIsConfirmedAndTheTemporaryFileStillGoes() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let recorder = AnnouncementRecorder()
        let model = model(kit, recorder: recorder)
        await model.start()
        await model.confirm()
        let url = try sharedURL(model)

        await model.shareFinished(activity: ExportViewModel.saveToFilesActivity, completed: true)

        #expect(model.state == .saved)
        #expect(!FileManager.default.fileExists(atPath: url.path))
        #expect(recorder.messages == ["File saved"] || recorder.messages == ["Fichier enregistré"])
    }

    @Test func sharingWithAnotherAppEndsWithoutASavedMessage() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let model = model(kit)
        await model.start()
        await model.confirm()
        await model.shareFinished(activity: "com.apple.UIKit.activity.Mail", completed: true)
        #expect(model.state == .idle)
        #expect(kit.exportFiles().isEmpty)
    }

    @Test func aFileThatCannotBeWrittenSaysSoAndTryAgainWorks() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let recorder = AnnouncementRecorder()
        let model = model(kit, recorder: recorder)
        await model.start()
        // A file where the export folder's parent should be: like a full disk, the write fails.
        let parent = kit.files.exportDirectory.deletingLastPathComponent()
        try Data().write(to: parent)

        await model.confirm()
        #expect(model.state == .failed(.file))
        #expect(recorder.messages.count == 1)

        try FileManager.default.removeItem(at: parent)
        await model.retry()
        _ = try sharedURL(model)
    }

    @Test func aSummaryThatCannotBeReadSaysSoAndTryAgainAsksAgain() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let broken = CabinetTransfer(
            store: FailingArchiveStore(), backups: InMemoryArchiveBackupStore(), clock: kit.clock, app: TransferHarness.app)
        let model = model(kit, transfer: broken)
        await model.start()
        #expect(model.state == .failed(.summary))
        await model.retry()
        #expect(model.state == .failed(.summary))
    }

    @Test func aCancelWhileTheFileIsMadeNeverReachesTheShareSheet() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let model = model(kit)
        await model.start()
        let confirming = Task { await model.confirm() }
        while model.state != .writing { await Task.yield() }
        model.cancel()
        await confirming.value
        #expect(model.state == .idle)
        #expect(kit.exportFiles().isEmpty)
    }

    @Test func retryDoesNothingUnlessSomethingFailed() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let model = model(kit)
        await model.retry()
        #expect(model.state == .idle)
        await model.shareFinished(activity: nil, completed: true)
        #expect(model.state == .idle)
        #expect(!model.isBusy)
    }
}
