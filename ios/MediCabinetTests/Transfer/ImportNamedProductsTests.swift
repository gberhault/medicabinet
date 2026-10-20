import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

/// Archive version 2 through the app: the products the household named travel in the export, in the automatic backup
/// and in a restore, and a version 1 file gets them filled from its boxes after the import (#57).
@MainActor
@Suite struct ImportNamedProductsTests {
    /// Where the import flow starts the named-products fill once the cabinet was replaced (`MainTabView`).
    @MainActor private final class Fill {
        private(set) var runs = 0
        private(set) var task: Task<Void, Never>?
        let environment: AppEnvironment

        init(_ environment: AppEnvironment) {
            self.environment = environment
        }

        func start() {
            runs += 1
            task = AppLaunch.startBackfill(environment)
        }

        func finished() async {
            await task?.value
        }
    }

    /// A reference database that is available and names nothing: every code is one the household has to name.
    private func emptyReference() throws -> InMemoryReferenceData {
        InMemoryReferenceData(dataDate: try #require(ReferenceDataDate(iso: "2026-10-18")))
    }

    private func file(_ kit: TransferTestKit, _ bytes: [UInt8]) throws -> URL {
        let folder = kit.root.appendingPathComponent("Picked", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent("\(UUID().uuidString).medicabinet")
        try Data(bytes).write(to: url)
        return url
    }

    private func model(_ kit: TransferTestKit, source: ImportSource, fill: Fill) -> ImportViewModel {
        ImportViewModel(
            request: ImportCoordinator.Request(source: source), transfer: kit.transfer, revision: kit.revision,
            announce: { _ in }, afterReplace: { fill.start() }
        )
    }

    private func importFile(_ kit: TransferTestKit, _ bytes: [UInt8], fill: Fill) async throws -> ImportViewModel {
        let model = model(kit, source: .file(try file(kit, bytes)), fill: fill)
        await model.start()
        await model.replace()
        return model
    }

    private func snapshot(_ kit: TransferTestKit) async throws -> CabinetContents {
        try await GRDBCabinetArchiveStore(database: kit.database).snapshot()
    }

    // MARK: Version 1

    @Test func aVersionOneImportFillsTheNamedProductsFromTheImportedBoxes() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.named.remember(gtin: "03409999999999", name: "Old product", category: .uncategorized, categoryOverridden: false)
        let fill = Fill(kit.environment(reference: try emptyReference()))
        let version1 = try SharedFixtures.goldenArchive(version: 1)

        let model = try await importFile(kit, version1, fill: fill)
        #expect(model.stage == .done)
        await fill.finished()

        #expect(fill.runs == 1)
        #expect(try await kit.named.backfillPending() == false)
        #expect(try await kit.named.namedProduct(gtin: "03409999999999") == nil, "the old cabinet's products are gone")
        let fromBox = try await kit.named.namedProduct(gtin: "03400936404571")
        #expect(fromBox?.name == "Produit 2")
        // The newest box with the other code names it, in or out of the cabinet but never a deleted one.
        let imported = try ArchiveDecoder.decode(version1).cabinet.boxes
        let newest = imported.filter { $0.deletedAt == nil && $0.box.product.gtin == "03400936404564" }
            .max { $0.box.createdAt < $1.box.createdAt }
        #expect(try await kit.named.namedProduct(gtin: "03400936404564")?.name == newest?.box.product.name)
        let stored = try await snapshot(kit).namedProducts
        #expect(stored.count == 2)
        #expect(stored.allSatisfy { $0.deletedAt == nil })
    }

    @Test func aBackupFromBeforeNamedProductsExistedAlsoGetsItsProductsFilled() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let fill = Fill(kit.environment(reference: try emptyReference()))
        let restoring = ArchiveBackupEntry(
            id: "pre-import-20261019-081530-123.medicabinet", takenAt: kit.clock.now, reason: .beforeImport, summary: nil
        )
        // A version 1 backup file, as an earlier app version wrote it.
        try FileManager.default.createDirectory(at: kit.backupsDirectory, withIntermediateDirectories: true)
        try Data(try SharedFixtures.goldenArchive(version: 1)).write(to: kit.backupsDirectory.appendingPathComponent(restoring.id))

        let model = model(kit, source: .backup(restoring), fill: fill)
        await model.start()
        await model.replace()
        await fill.finished()

        #expect(model.stage == .done)
        #expect(try await kit.named.namedProduct(gtin: "03400936404571")?.name == "Produit 2")
        #expect(try await kit.named.backfillPending() == false)
    }

    // MARK: Version 2

    @Test func aVersionTwoImportKeepsTheFilesNamedProductsAndTheFillHasNothingToDo() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        let fill = Fill(kit.environment(reference: try emptyReference()))
        let golden = try SharedFixtures.goldenArchive()
        let expected = try ArchiveDecoder.decode(golden).cabinet

        let model = try await importFile(kit, golden, fill: fill)
        await fill.finished()

        #expect(model.stage == .done)
        #expect(model.result?.imported.namedProducts == 2)
        #expect(try await kit.named.backfillPending() == false)
        // The boxes' codes are unknown to this reference, yet no product was invented: the file's three are all there is.
        #expect(try await snapshot(kit) == expected)
        #expect(try await snapshot(kit).namedProducts.count == 3)
        let chosen = try await kit.named.namedProduct(gtin: "03401234567890")
        #expect(chosen?.name == "Crème solaire SPF 50")
        #expect(chosen?.categoryOverridden == true)
    }

    @Test func theFillIsOnlyStartedAfterASuccessfulReplace() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        let fill = Fill(kit.environment(reference: try emptyReference()))
        let model = model(kit, source: .file(try file(kit, try SharedFixtures.goldenArchive())), fill: fill)
        await model.start()
        // A file where the backups folder should be: the backup fails and nothing is replaced.
        try Data().write(to: kit.backupsDirectory)

        await model.replace()

        #expect(model.stage == .failed(.backupFailed))
        #expect(fill.runs == 0)
    }

    // MARK: Backup, restore, export

    @Test func theAutomaticBackupCarriesTheNamedProductsAndARestoreBringsThemBack() async throws {
        let kit = try await TransferTestKit.make()
        defer { kit.cleanUp() }
        try await kit.addBox("Doliprane")
        try await kit.named.remember(gtin: "03401111111111", name: "Crème", category: .uncategorized, categoryOverridden: false)
        try await kit.named.remember(gtin: "03402222222222", name: "Test", category: .uncategorized, categoryOverridden: false)
        let test = try #require(try await kit.named.namedProduct(gtin: "03402222222222"))
        try await kit.named.forget(id: test.id)
        let before = try await snapshot(kit)
        #expect(before.namedProducts.count == 2)
        let fill = Fill(kit.environment(reference: try emptyReference()))

        let importing = try await importFile(kit, try SharedFixtures.goldenArchive(), fill: fill)
        let entry = try #require(importing.result?.backup)
        let saved = try ArchiveDecoder.decode(try await kit.transfer.readBackup(entry.id))
        #expect(saved.cabinet.namedProducts.count == 2)
        #expect(saved.cabinet.namedProducts.contains { $0.deletedAt != nil }, "the forgotten product is in the backup")

        let restoring = model(kit, source: .backup(entry), fill: fill)
        await restoring.start()
        await restoring.replace()
        await fill.finished()

        #expect(restoring.stage == .done)
        #expect(try await snapshot(kit) == before)
        #expect(try await kit.named.namedProduct(gtin: "03401111111111")?.name == "Crème")
        #expect(try await kit.named.namedProduct(gtin: "03402222222222") == nil, "a forgotten product stays forgotten")
    }

    @Test func anExportThenAnImportOnAnotherPhoneKeepsEveryNamedProductForgottenOnesIncluded() async throws {
        let first = try await TransferTestKit.make()
        let second = try await TransferTestKit.make()
        defer { first.cleanUp(); second.cleanUp() }
        try await first.addBox("Doliprane")
        try await first.named.remember(gtin: "03401111111111", name: "Crème", category: .skinWound, categoryOverridden: true)
        try await first.named.remember(gtin: "03402222222222", name: "Test", category: .uncategorized, categoryOverridden: false)
        let test = try #require(try await first.named.namedProduct(gtin: "03402222222222"))
        try await first.named.forget(id: test.id)
        let exported = try await first.transfer.exportArchive()
        let fill = Fill(second.environment(reference: try emptyReference()))

        let model = try await importFile(second, exported, fill: fill)
        await fill.finished()

        #expect(model.stage == .done)
        #expect(model.result?.imported.namedProducts == 1)
        #expect(try await snapshot(second) == (try await snapshot(first)))
        #expect(try await second.transfer.exportArchive() == exported)
        #expect(try await second.named.namedProduct(gtin: "03401111111111")?.category == .skinWound)
    }
}
