import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

/// The import flow (I1–I10, V5, B3, B5): nothing changes before the confirmation; every refusal has its message.
@MainActor
@Suite struct ImportViewModelTests {
    private func file(_ kit: TransferTestKit, _ bytes: [UInt8], named name: String = "cabinet.medicabinet") throws -> URL {
        let folder = kit.root.appendingPathComponent("Picked", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent(name)
        try Data(bytes).write(to: url)
        return url
    }

    private func model(
        _ kit: TransferTestKit, source: ImportSource, transfer: CabinetTransfer? = nil, limits: ArchiveLimits = .default,
        recorder: AnnouncementRecorder = AnnouncementRecorder()
    ) -> ImportViewModel {
        ImportViewModel(
            request: ImportCoordinator.Request(source: source), transfer: transfer ?? kit.transfer,
            revision: kit.revision, limits: limits, announce: { recorder.record($0) }
        )
    }

    private func golden(_ kit: TransferTestKit) throws -> URL {
        try file(kit, try SharedFixtures.goldenArchive())
    }

    private func snapshot(_ kit: TransferTestKit) async throws -> CabinetContents {
        try await GRDBCabinetArchiveStore(database: kit.database).snapshot()
    }

    // MARK: Preview

    @Test func theGoldenFileIsPreviewedAgainstThePhoneAndNothingChanges() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        let before = try await snapshot(kit)
        let model = model(kit, source: .file(try golden(kit)))

        await model.start()

        #expect(model.stage == .preview)
        let preview = try #require(model.preview)
        let expected = try ArchiveDecoder.decode(try SharedFixtures.goldenArchive()).cabinet.summary
        #expect(preview.file == expected)
        #expect(preview.archive.sourceFormatVersion == 2)
        #expect(preview.file.namedProducts == 2)
        #expect(preview.current == CabinetSummary(locations: 1, inCabinet: 1, removed: 0, deleted: 0, unknownExpiry: 1))
        #expect(preview.willBackUp)
        #expect(try await snapshot(kit) == before)
        #expect(kit.backupFiles().isEmpty)
    }

    @Test func aPhoneWithNothingToLoseIsToldNoBackupIsNeeded() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let model = model(kit, source: .file(try golden(kit)))
        await model.start()
        #expect(model.preview?.willBackUp == false)

        await model.replace()

        #expect(model.stage == .done)
        #expect(model.result?.backup == nil)
        #expect(kit.backupFiles().isEmpty)
    }

    // MARK: Replace

    @Test func confirmingBacksUpThenReplacesAndEveryScreenIsTold() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        let recorder = AnnouncementRecorder()
        let model = model(kit, source: .file(try golden(kit)), recorder: recorder)
        let revision = kit.revision
        await model.start()

        await model.replace()

        #expect(model.stage == .done)
        #expect(try await snapshot(kit) == (try ArchiveDecoder.decode(try SharedFixtures.goldenArchive()).cabinet))
        #expect(model.result?.backup?.reason == .beforeImport)
        #expect(kit.backupFiles().count == 1)
        #expect(kit.backupFiles().first?.hasPrefix("pre-import-") == true)
        #expect(revision.value == 1)
        #expect(recorder.messages == [TransferText.string("transfer.import.backingUp"), TransferText.string("transfer.import.done")])
    }

    @Test func replaceIsIgnoredBeforeThePreview() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let model = model(kit, source: .file(try golden(kit)))
        await model.replace()
        #expect(model.stage == .reading)
        #expect(model.result == nil)
    }

    @Test func aBackupThatCannotBeSavedStopsTheImportAndTryAgainFinishesIt() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        let before = try await snapshot(kit)
        let model = model(kit, source: .file(try golden(kit)))
        await model.start()
        // A file where the backups folder should be: like a full disk, the backup cannot be written.
        try Data().write(to: kit.backupsDirectory)

        await model.replace()

        #expect(model.stage == .failed(.backupFailed))
        #expect(try await snapshot(kit) == before)

        try FileManager.default.removeItem(at: kit.backupsDirectory)
        await model.retry()
        #expect(model.stage == .done)
        #expect(kit.backupFiles().count == 1)
    }

    @Test func aReplaceThatFailsLeavesTheCabinetAndOffersTryAgain() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let refusing = CabinetTransfer(
            store: RefusingReplaceStore(inner: GRDBCabinetArchiveStore(database: kit.database)),
            backups: FileArchiveBackupStore(directory: kit.backupsDirectory), clock: kit.clock, app: TransferHarness.app
        )
        let model = model(kit, source: .file(try golden(kit)), transfer: refusing)
        await model.start()

        await model.replace()

        #expect(model.stage == .failed(.replaceFailed))
        #expect(model.stage != .done)
        await model.retry()
        #expect(model.stage == .failed(.replaceFailed))
    }

    // MARK: Restore

    @Test func aBackupIsRestoredLikeAnImportAndBacksUpAsBeforeARestore() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        let first = try await snapshot(kit)
        let importing = model(kit, source: .file(try golden(kit)))
        await importing.start()
        await importing.replace()
        let entry = try #require(importing.result?.backup)

        let restoring = model(kit, source: .backup(entry))
        #expect(restoring.restoring == entry)
        await restoring.start()
        await restoring.replace()

        #expect(restoring.stage == .done)
        #expect(try await snapshot(kit) == first)
        #expect(kit.backupFiles().contains { $0.hasPrefix("pre-restore-") })
    }

    @Test func aBackupThatIsGoneCannotBeRead() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let entry = ArchiveBackupEntry(
            id: "pre-import-20270115-080000-000.medicabinet", takenAt: kit.clock.now, reason: .beforeImport, summary: nil)
        let model = model(kit, source: .backup(entry))
        await model.start()
        #expect(model.stage == .failed(.readFailed))
    }

    // MARK: Refusals (strings.md mapping)

    @Test func aFileThatIsNotJSONIsNotAnArchive() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let recorder = AnnouncementRecorder()
        let model = model(kit, source: .file(try file(kit, Array("not json".utf8))), recorder: recorder)
        await model.start()
        #expect(model.stage == .failed(.notArchive))
        #expect(recorder.messages == [TransferText.string("transfer.error.notArchive")])
    }

    @Test func aFileFromANewerAppAsksForAnUpdate() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let newer = Array(#"{"archive":"medicabinet","formatVersion":3}"#.utf8)
        let model = model(kit, source: .file(try file(kit, newer)))
        await model.start()
        #expect(model.stage == .failed(.newerVersion))
        #expect(!ImportFailure.newerVersion.canRetry)
    }

    @Test func aDamagedFileIsRefusedWholeAndNothingChanges() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        let before = try await snapshot(kit)
        var bytes = try SharedFixtures.goldenArchive()
        bytes.removeLast(40)
        let model = model(kit, source: .file(try file(kit, bytes)))
        await model.start()
        #expect(model.stage == .failed(.notArchive) || model.stage == .failed(.damaged))
        #expect(try await snapshot(kit) == before)
    }

    @Test func aFileOverTheSizeLimitIsRefusedUnread() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        var limits = ArchiveLimits()
        limits.maxFileBytes = 100
        let model = model(kit, source: .file(try golden(kit)), limits: limits)
        await model.start()
        #expect(model.stage == .failed(.tooLarge))
    }

    @Test func aFileThatCannotBeReadOffersTryAgain() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let missing = kit.root.appendingPathComponent("Picked/missing.medicabinet")
        let model = model(kit, source: .file(missing))
        await model.start()
        #expect(model.stage == .failed(.readFailed))

        try FileManager.default.createDirectory(at: missing.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: try golden(kit), to: missing)
        await model.retry()
        #expect(model.stage == .preview)
    }

    @Test func aCabinetThatCannotBeReadStopsThePreview() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let broken = CabinetTransfer(
            store: FailingArchiveStore(), backups: InMemoryArchiveBackupStore(), clock: kit.clock, app: TransferHarness.app)
        let model = model(kit, source: .file(try golden(kit)), transfer: broken)
        await model.start()
        #expect(model.stage == .failed(.replaceFailed))
        await model.retry()
        #expect(model.stage == .failed(.replaceFailed))
    }

    @Test(arguments: ArchiveErrorKind.allCases)
    func everyRefusalKindHasItsMessage(kind: ArchiveErrorKind) {
        let failure = ImportFailure.from(kind)
        let expected: ImportFailure = switch kind {
        case .tooLarge, .tooManyItems: .tooLarge
        case .notJSON, .notArchive: .notArchive
        case .newerVersion: .newerVersion
        default: .damaged
        }
        #expect(failure == expected)
        #expect(!failure.canRetry)
        #expect(Bundle.main.localizedString(forKey: failure.messageKey, value: "?", table: nil) != "?")
    }

    @Test func retryDoesNothingForARefusedFile() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let model = model(kit, source: .file(try file(kit, Array("x".utf8))))
        await model.start()
        let stage = model.stage
        await model.retry()
        #expect(model.stage == stage)
        #expect(!model.isWorking)
        #expect(model.restoring == nil)
    }
}

/// Reads like the real store but refuses every replace, like a database that went read-only.
private struct RefusingReplaceStore: CabinetArchiveStore {
    struct Refused: Error {}
    let inner: any CabinetArchiveStore

    func snapshot() async throws -> CabinetContents {
        try await inner.snapshot()
    }

    func replace(with snapshot: CabinetContents, beforeReplacing: @Sendable (CabinetContents) throws -> Void) async throws {
        throw Refused()
    }
}
