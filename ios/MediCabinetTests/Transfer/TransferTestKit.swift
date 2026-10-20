import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
@testable import MediCabinet

/// A cabinet on a real database file, its automatic backups folder and the export / Inbox folders, all in a fresh
/// temporary directory.
struct TransferTestKit {
    static let now = Date(timeIntervalSince1970: 1_800_000_000)   // 2027-01-15 08:00:00 UTC

    let root: URL
    let database: AppDatabase
    let clock: MutableClock
    let locations: GRDBLocationRepository
    let boxes: GRDBBoxRepository
    let transfer: CabinetTransfer
    let files: ArchiveFiles
    let backupsDirectory: URL
    let home: Location
    /// One revision for the kit, like the one environment of the app.
    let revision = CabinetRevision()

    static func make() async throws -> TransferTestKit {
        let (database, root) = try AppDatabase.temporary()
        let clock = MutableClock(now)
        let locations = GRDBLocationRepository(database: database, clock: clock)
        let home = try await locations.ensureDefault(named: "Home")
        let backupsDirectory = BackupService.defaultDirectory(in: root)
        return TransferTestKit(
            root: root, database: database, clock: clock, locations: locations,
            boxes: GRDBBoxRepository(database: database, clock: clock),
            transfer: AppTransfer.make(
                database: database, backupsDirectory: backupsDirectory, clock: clock, app: TransferHarness.app
            ),
            files: ArchiveFiles(
                exportDirectory: root.appendingPathComponent("tmp/Export", isDirectory: true),
                inboxDirectory: root.appendingPathComponent("Documents/Inbox", isDirectory: true)
            ),
            backupsDirectory: backupsDirectory,
            home: home
        )
    }

    var environment: AppEnvironment {
        AppEnvironment(
            locations: locations, boxes: boxes, named: GRDBNamedProductRepository(database: database, clock: clock), clock: clock,
            reference: InMemoryReferenceData(status: .unavailable(.missing)), timeZone: { .gmt }, transfer: transfer,
            revision: revision
        )
    }

    @discardableResult
    func addBox(_ name: String, expiry: ExpiryDate? = nil) async throws -> Box {
        let box = Box(product: ProductRef(name: name), locationID: home.id, expiry: expiry, createdAt: clock.now)
        try await boxes.insert(box)
        return box
    }

    func exportFiles() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: files.exportDirectory.path)) ?? []).sorted()
    }

    func backupFiles() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: backupsDirectory.path)) ?? []).sorted()
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: root)
    }
}

/// Records what VoiceOver would have announced.
@MainActor
final class AnnouncementRecorder {
    private(set) var messages: [String] = []

    func record(_ message: String) {
        messages.append(message)
    }
}

/// A store that cannot be read or written, like a database whose disk went away.
struct FailingArchiveStore: CabinetArchiveStore {
    struct Failure: Error {}

    func snapshot() async throws -> CabinetContents { throw Failure() }

    func replace(with snapshot: CabinetContents, beforeReplacing: @Sendable (CabinetContents) throws -> Void) async throws {
        throw Failure()
    }
}
