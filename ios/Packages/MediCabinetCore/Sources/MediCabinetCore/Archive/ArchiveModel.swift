import Foundation

public enum ArchiveFormat {
    public static let magic = "medicabinet"
    /// The newest `formatVersion` this app reads and writes: one reader per version (see `ArchiveDecoder`).
    public static let currentVersion = ArchiveDecoder.readers.count
    public static let fileExtension = "medicabinet"
    public static let mimeType = "application/vnd.medicabinet+json"
}

/// Named defaults, well above any real cabinet (a box is about 650 bytes in the file; a household
/// adds a few hundred boxes a year). The encoder obeys them too, so the app never writes a file
/// it would refuse.
public struct ArchiveLimits: Sendable {
    public var maxFileBytes = 32 * 1024 * 1024
    public var maxBoxes = 20_000
    public var maxLocations = 200
    /// A decoded string: longer than any field allows (200 code points of 4 bytes).
    public var maxStringBytes = 4_096

    public static let `default` = ArchiveLimits()

    public init() {}
}

/// A box as the archive carries it: removed boxes keep their `removal`, deleted ones (added by
/// mistake, tombstones for a future sync) also have a `deletedAt`.
public struct ArchivedBox: Hashable, Sendable {
    public var box: Box
    public var deletedAt: Date?

    public init(box: Box, deletedAt: Date? = nil) {
        self.box = box
        self.deletedAt = deletedAt
    }
}

/// What a cabinet holds, in numbers (the import preview).
public struct CabinetSummary: Hashable, Sendable {
    public var locations: Int
    /// Boxes still in the cabinet (not removed, not deleted).
    public var inCabinet: Int
    /// Used up or thrown away.
    public var removed: Int
    /// Added by mistake (tombstones).
    public var deleted: Int
    /// Boxes in the cabinet with no expiry date.
    public var unknownExpiry: Int

    public init(locations: Int, inCabinet: Int, removed: Int, deleted: Int, unknownExpiry: Int) {
        self.locations = locations
        self.inCabinet = inCabinet
        self.removed = removed
        self.deleted = deleted
        self.unknownExpiry = unknownExpiry
    }
}

/// The whole cabinet data: every location and every box, removed and deleted ones included.
public struct CabinetSnapshot: Hashable, Sendable {
    public var locations: [Location]
    public var boxes: [ArchivedBox]

    public init(locations: [Location], boxes: [ArchivedBox]) {
        self.locations = locations
        self.boxes = boxes
    }

    public var summary: CabinetSummary {
        let live = boxes.filter { $0.deletedAt == nil }
        let inCabinet = live.filter { $0.box.removal == nil }
        return CabinetSummary(
            locations: locations.count,
            inCabinet: inCabinet.count,
            removed: live.count - inCabinet.count,
            deleted: boxes.count - live.count,
            unknownExpiry: inCabinet.filter { $0.box.expiry == nil }.count
        )
    }

    /// False only when there is nothing to lose: no box of any kind and a single (default) location.
    public var needsBackup: Bool {
        !(boxes.isEmpty && locations.count == 1)
    }
}

/// Who wrote the archive.
public struct ArchiveApp: Hashable, Sendable {
    public static let platforms = ["ios", "android"]

    public var name: String
    public var platform: String
    public var version: String
    public var build: String

    public init(name: String, platform: String, version: String, build: String) {
        self.name = name
        self.platform = platform
        self.version = version
        self.build = build
    }
}

public struct Archive: Hashable, Sendable {
    public var exportedAt: Date
    public var app: ArchiveApp
    public var cabinet: CabinetSnapshot
    /// The `formatVersion` of the file this was read from (the import preview shows it); ignored when encoding.
    public var sourceFormatVersion: Int

    public init(exportedAt: Date, app: ArchiveApp, cabinet: CabinetSnapshot, sourceFormatVersion: Int = ArchiveFormat.currentVersion) {
        self.exportedAt = exportedAt
        self.app = app
        self.cabinet = cabinet
        self.sourceFormatVersion = sourceFormatVersion
    }
}
