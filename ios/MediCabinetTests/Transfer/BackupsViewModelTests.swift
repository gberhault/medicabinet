import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

/// Settings → Automatic backups (B4).
@MainActor
@Suite struct BackupsViewModelTests {
    @Test func noBackupsYetIsAnEmptyList() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let model = BackupsViewModel(transfer: kit.transfer)
        #expect(model.state == .loading)
        await model.load()
        #expect(model.state == .loaded([]))
    }

    @Test func backupsAreListedNewestFirstWithTheirReasonAndCount() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        let golden = try SharedFixtures.goldenArchive()
        _ = try await kit.transfer.apply(try await kit.transfer.preview(golden), reason: .beforeImport)
        kit.clock.advance(by: 60)
        _ = try await kit.transfer.apply(try await kit.transfer.preview(golden), reason: .beforeRestore)
        let model = BackupsViewModel(transfer: kit.transfer)

        await model.load()

        guard case let .loaded(entries) = model.state else { Issue.record("expected a list"); return }
        #expect(entries.map(\.reason) == [.beforeRestore, .beforeImport])
        #expect(entries.last?.summary?.inCabinet == 1)
        #expect(entries.first?.takenAt == kit.clock.now)
    }

    @Test func anUnreadableBackupIsListedNotHidden() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        let result = try await kit.transfer.apply(
            try await kit.transfer.preview(try SharedFixtures.goldenArchive()), reason: .beforeImport)
        let entry = try #require(result.backup)
        try Data("{".utf8).write(to: kit.backupsDirectory.appendingPathComponent(entry.id))
        let model = BackupsViewModel(transfer: kit.transfer)

        await model.load()

        guard case let .loaded(entries) = model.state else { Issue.record("expected a list"); return }
        #expect(entries.count == 1)
        #expect(entries.first?.summary == nil)
    }

    @Test func aFolderThatCannotBeReadSaysSo() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try Data().write(to: kit.backupsDirectory)
        let model = BackupsViewModel(transfer: kit.transfer)
        await model.load()
        #expect(model.state == .failed)

        try FileManager.default.removeItem(at: kit.backupsDirectory)
        await model.load()
        #expect(model.state == .loaded([]))
    }

    @Test func eachReasonHasItsLabel() {
        #expect(BackupsViewModel.reasonKey(.beforeImport) == "transfer.backups.beforeImport")
        #expect(BackupsViewModel.reasonKey(.beforeRestore) == "transfer.backups.beforeRestore")
    }
}
