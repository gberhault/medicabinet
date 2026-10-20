import Foundation
import MediCabinetCore

/// In-memory fake of `CabinetArchiveStore`; also documents the expected behaviour (the contract in
/// `ArchiveStoreContract` runs against it and against every real store).
public actor InMemoryArchiveStore: CabinetArchiveStore {
    private var cabinet: CabinetContents

    public init(_ cabinet: CabinetContents = CabinetContents(locations: [], boxes: [])) {
        self.cabinet = cabinet
    }

    public func snapshot() async throws -> CabinetContents {
        cabinet
    }

    public func replace(
        with snapshot: CabinetContents, beforeReplacing: @Sendable (CabinetContents) throws -> Void
    ) async throws {
        try beforeReplacing(cabinet)
        try Self.requireHoldable(snapshot)
        cabinet = snapshot
    }

    /// The constraints a database enforces: unique ids, one default location, boxes in known locations.
    private static func requireHoldable(_ snapshot: CabinetContents) throws {
        guard Set(snapshot.locations.map(\.location.id)).count == snapshot.locations.count,
              Set(snapshot.boxes.map(\.box.id)).count == snapshot.boxes.count
        else { throw RepositoryError.duplicate }
        guard snapshot.locations.filter(\.location.isDefault).count <= 1 else { throw RepositoryError.duplicateDefault }
        let known = Set(snapshot.locations.map(\.location.id))
        guard snapshot.boxes.allSatisfy({ known.contains($0.box.locationID) }) else { throw RepositoryError.unknownLocation }
    }
}

/// In-memory fake of `ArchiveBackupStore`, with switches to make the next operations fail.
public final class InMemoryArchiveBackupStore: ArchiveBackupStore, @unchecked Sendable {
    public struct Failure: Error, Equatable {
        public init() {}
    }

    private struct Stored {
        var bytes: [UInt8]
        let reason: BackupReason
        let takenAt: Date
    }

    private let lock = NSLock()
    private var files: [String: Stored] = [:]
    private var counter = 0
    private var failWrites = false
    private var failPrunes = false

    public init() {}

    /// Makes `write` throw, as a full disk would.
    public func setFailingWrites(_ failing: Bool) {
        locked { failWrites = failing }
    }

    public func setFailingPrunes(_ failing: Bool) {
        locked { failPrunes = failing }
    }

    /// Damages a stored file so it no longer decodes.
    public func corrupt(_ id: String) {
        locked { files[id]?.bytes = [0x7B] }
    }

    public var count: Int {
        locked { files.count }
    }

    public func write(_ bytes: [UInt8], reason: BackupReason, takenAt: Date) throws -> ArchiveBackupEntry {
        try locked {
            if failWrites { throw Failure() }
            // Like the real store: what was written must read back as an archive.
            let archive = try ArchiveDecoder.decode(bytes)
            counter += 1
            let id = "\(reason.rawValue)-\(counter)"
            files[id] = Stored(bytes: bytes, reason: reason, takenAt: takenAt)
            return ArchiveBackupEntry(id: id, takenAt: takenAt, reason: reason, summary: archive.cabinet.summary)
        }
    }

    public func list() async throws -> [ArchiveBackupEntry] {
        locked {
            files.sorted { ($0.value.takenAt, $0.key) > ($1.value.takenAt, $1.key) }.map { id, stored in
                let summary = (try? ArchiveDecoder.decode(stored.bytes))?.cabinet.summary
                return ArchiveBackupEntry(id: id, takenAt: stored.takenAt, reason: stored.reason, summary: summary)
            }
        }
    }

    public func read(_ id: String) async throws -> [UInt8] {
        try locked {
            guard let stored = files[id] else { throw Failure() }
            return stored.bytes
        }
    }

    public func prune(keeping count: Int) async throws {
        try locked {
            if failPrunes { throw Failure() }
            let newestFirst = files.sorted { ($0.value.takenAt, $0.key) > ($1.value.takenAt, $1.key) }
            for (id, _) in newestFirst.dropFirst(max(count, 0)) { files[id] = nil }
        }
    }

    private func locked<Result>(_ body: () throws -> Result) rethrows -> Result {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }
}
