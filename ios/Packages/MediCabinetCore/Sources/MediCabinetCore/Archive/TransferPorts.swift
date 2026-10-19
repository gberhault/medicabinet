import Foundation

/// The whole cabinet, for export and import. Implementations: an in-memory fake (`MediCabinetCoreTestSupport`),
/// GRDB in the app. The shared `fixtures/archive-store-contract.json` lists the checks each must pass.
public protocol CabinetArchiveStore: Sendable {
    /// Every location and every box (in the cabinet, removed, deleted), read in one transaction.
    func snapshot() async throws -> CabinetSnapshot

    /// In **one write transaction**: read the current cabinet, call `beforeReplacing` with it (anything it throws
    /// rolls everything back and is rethrown), replace all rows with `snapshot`, recompute derived values.
    /// Ids, fields and dates are kept as given. A snapshot the store cannot hold (a box in an unknown location, a
    /// duplicate id, two default locations) throws the matching `RepositoryError` and changes nothing.
    func replace(with snapshot: CabinetSnapshot, beforeReplacing: @Sendable (CabinetSnapshot) throws -> Void) async throws
}

/// Why an automatic backup was taken.
public enum BackupReason: String, Sendable, CaseIterable {
    case beforeImport
    case beforeRestore
}

/// One automatic backup file, as listed in Settings.
public struct ArchiveBackupEntry: Hashable, Sendable, Identifiable {
    public let id: String
    public let takenAt: Date
    public let reason: BackupReason
    /// `nil` when the file cannot be read any more (it is still listed, never hidden).
    public let summary: CabinetSummary?

    public init(id: String, takenAt: Date, reason: BackupReason, summary: CabinetSummary?) {
        self.id = id
        self.takenAt = takenAt
        self.reason = reason
        self.summary = summary
    }
}

/// The automatic backup files (archives in private storage). Implementations: an in-memory fake, the file system in the app.
public protocol ArchiveBackupStore: Sendable {
    /// Writes the archive, reads it back and decodes it; throws if any step fails. Synchronous on purpose: it runs
    /// inside the replace transaction.
    func write(_ bytes: [UInt8], reason: BackupReason, takenAt: Date) throws -> ArchiveBackupEntry

    /// Newest first.
    func list() async throws -> [ArchiveBackupEntry]

    func read(_ id: String) async throws -> [UInt8]

    /// Deletes all but the `count` newest.
    func prune(keeping count: Int) async throws
}
