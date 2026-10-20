import Foundation

/// What an import is about to do, shown before the user confirms (I1).
public struct ImportPreview: Sendable {
    public let archive: Archive
    /// The file's cabinet.
    public let file: CabinetSummary
    /// The phone's cabinet when the preview was made (informative: `apply` backs up the cabinet as it is then).
    public let current: CabinetSummary
    /// False when the phone's cabinet is empty, so there is nothing to lose (B3).
    public let willBackUp: Bool
}

public struct ImportResult: Sendable {
    public let imported: CabinetSummary
    /// The automatic backup of the replaced cabinet, `nil` when there was nothing to back up.
    public let backup: ArchiveBackupEntry?
    /// True when old backups could not be deleted afterwards: the import is done, the app should only log it (B2).
    public let pruneFailed: Bool
}

public enum TransferError: Error, Equatable, Sendable {
    /// The automatic backup could not be written (or read back): nothing was changed.
    case backupFailed
}

/// The export / import / restore use case, stateless and testable on the desktop: ports for the cabinet and the
/// automatic backups, an injected clock and the identity written into archives.
public struct CabinetTransfer: Sendable {
    /// How many automatic backups are kept (B2).
    public static let defaultKeepCount = 3

    private let store: any CabinetArchiveStore
    private let backupStore: any ArchiveBackupStore
    private let clock: any Clock
    private let app: ArchiveApp
    private let limits: ArchiveLimits
    private let keepCount: Int

    public init(
        store: any CabinetArchiveStore, backups: any ArchiveBackupStore, clock: any Clock, app: ArchiveApp,
        limits: ArchiveLimits = .default, keepCount: Int = CabinetTransfer.defaultKeepCount
    ) {
        self.store = store
        self.backupStore = backups
        self.clock = clock
        self.app = app
        self.limits = limits
        self.keepCount = keepCount
    }

    public func currentSummary() async throws -> CabinetSummary {
        try await store.snapshot().summary
    }

    /// The whole cabinet as an archive: one snapshot, validated and encoded.
    public func exportArchive() async throws -> [UInt8] {
        let snapshot = try await store.snapshot()
        return try ArchiveEncoder.encode(Archive(exportedAt: clock.now, app: app, cabinet: snapshot), limits: limits)
    }

    /// Reads and validates a file (any known version) and compares it with the cabinet on the phone. Throws an
    /// `ArchiveError` for a file that must be refused; nothing is written.
    public func preview(_ bytes: [UInt8]) async throws -> ImportPreview {
        let archive = try ArchiveDecoder.decode(bytes, limits: limits)
        let snapshot = try await store.snapshot()
        // The snapshot, not its summary: a forgotten named product is not counted there but is still worth a backup.
        return ImportPreview(
            archive: archive, file: archive.cabinet.summary, current: snapshot.summary, willBackUp: snapshot.needsBackup
        )
    }

    /// Replaces the cabinet with the previewed archive in one transaction, after backing up the cabinet as it is
    /// when the transaction starts (so nothing can land between the backup and the replace, I5). A backup failure
    /// stops everything (`TransferError.backupFailed`) and leaves the cabinet as it was.
    public func apply(_ preview: ImportPreview, reason: BackupReason) async throws -> ImportResult {
        let now = clock.now
        let written = Captured<ArchiveBackupEntry>()
        let backupStore = backupStore
        let (app, limits) = (app, limits)
        try await store.replace(with: preview.archive.cabinet) { current in
            guard current.needsBackup else { return }
            do {
                let bytes = try ArchiveEncoder.encode(Archive(exportedAt: now, app: app, cabinet: current), limits: limits)
                written.set(try backupStore.write(bytes, reason: reason, takenAt: now))
            } catch {
                throw TransferError.backupFailed
            }
        }
        let pruneFailed = await prune()
        return ImportResult(imported: preview.archive.cabinet.summary, backup: written.value, pruneFailed: pruneFailed)
    }

    public func backups() async throws -> [ArchiveBackupEntry] {
        try await backupStore.list()
    }

    public func readBackup(_ id: String) async throws -> [UInt8] {
        try await backupStore.read(id)
    }

    /// `MediCabinet-YYYY-MM-DD.medicabinet`, the date being the day of the export where the user is (E4).
    public static func fileName(for date: Date, in timeZone: TimeZone) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let year = calendar.component(.year, from: date)
        let month = calendar.component(.month, from: date)
        let day = calendar.component(.day, from: date)
        return "MediCabinet-\(String(format: "%04d-%02d-%02d", year, month, day)).\(ArchiveFormat.fileExtension)"
    }

    /// Old backups go only after the new one is written and the replace committed; a failure here is reported, not thrown.
    private func prune() async -> Bool {
        do {
            try await backupStore.prune(keeping: keepCount)
            return false
        } catch {
            return true
        }
    }
}

/// Carries a value out of the synchronous `beforeReplacing` callback. The callback runs to completion before
/// `replace` returns, so there is never concurrent access; the lock only makes that explicit to the compiler.
private final class Captured<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Value?

    func set(_ value: Value) {
        lock.lock()
        defer { lock.unlock() }
        stored = value
    }

    var value: Value? {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }
}
