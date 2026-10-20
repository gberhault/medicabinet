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
    public var maxNamedProducts = 20_000
    /// A decoded string: longer than any field allows (200 code points of 4 bytes).
    public var maxStringBytes = 4_096

    public static let `default` = ArchiveLimits()

    public init() {}
}

/// A location as the archive carries it: one the user deleted keeps its row, with `deletedAt`, because removed and
/// deleted boxes still point at it (schema v3).
public struct ArchivedLocation: Hashable, Sendable {
    public var location: Location
    public var deletedAt: Date?

    public init(location: Location, deletedAt: Date? = nil) {
        self.location = location
        self.deletedAt = deletedAt
    }
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

/// A named product as the archive carries it (`formatVersion` 2): one the user forgot keeps its row, with `deletedAt`,
/// so a later sync can tell "forgotten" from "never existed".
public struct ArchivedNamedProduct: Hashable, Sendable {
    public var product: NamedProduct
    public var deletedAt: Date?

    public init(product: NamedProduct, deletedAt: Date? = nil) {
        self.product = product
        self.deletedAt = deletedAt
    }
}

/// What a cabinet holds, in numbers (the import preview).
public struct CabinetSummary: Hashable, Sendable {
    /// Locations that are not deleted.
    public var locations: Int
    /// Boxes still in the cabinet (not removed, not deleted).
    public var inCabinet: Int
    /// Used up or thrown away.
    public var removed: Int
    /// Added by mistake (tombstones).
    public var deleted: Int
    /// Boxes in the cabinet with no expiry date.
    public var unknownExpiry: Int
    /// Products the household named and has not forgotten.
    public var namedProducts: Int

    public init(locations: Int, inCabinet: Int, removed: Int, deleted: Int, unknownExpiry: Int, namedProducts: Int = 0) {
        self.locations = locations
        self.inCabinet = inCabinet
        self.removed = removed
        self.deleted = deleted
        self.unknownExpiry = unknownExpiry
        self.namedProducts = namedProducts
    }

    /// False when there is nothing to lose: no box of any kind, no named product and no location besides (at most) the
    /// default one. Deleted locations are not counted, like everywhere the user sees them. Forgotten named products are
    /// not in a summary: `CabinetContents.needsBackup` also counts them.
    public var needsBackup: Bool {
        locations > 1 || inCabinet + removed + deleted + namedProducts > 0
    }
}

/// The whole cabinet data: every location, every box and every named product, removed, deleted and forgotten ones
/// included.
public struct CabinetContents: Hashable, Sendable {
    public var locations: [ArchivedLocation]
    public var boxes: [ArchivedBox]
    public var namedProducts: [ArchivedNamedProduct]
    /// True when the named products still have to be filled from the boxes: the archive was a version 1 file, which has
    /// none (`NamedProductBackfillRunner` does it after the import). Never part of a file: the decoder sets it, a store
    /// snapshot is always false, and `replace` turns it into the backfill marker.
    public var namedProductsNeedFill: Bool

    public init(
        locations: [ArchivedLocation], boxes: [ArchivedBox], namedProducts: [ArchivedNamedProduct] = [],
        namedProductsNeedFill: Bool = false
    ) {
        self.locations = locations
        self.boxes = boxes
        self.namedProducts = namedProducts
        self.namedProductsNeedFill = namedProductsNeedFill
    }

    public var summary: CabinetSummary {
        let live = boxes.filter { $0.deletedAt == nil }
        let inCabinet = live.filter { $0.box.removal == nil }
        return CabinetSummary(
            locations: locations.filter { $0.deletedAt == nil }.count,
            inCabinet: inCabinet.count,
            removed: live.count - inCabinet.count,
            deleted: boxes.count - live.count,
            unknownExpiry: inCabinet.filter { $0.box.expiry == nil }.count,
            namedProducts: namedProducts.filter { $0.deletedAt == nil }.count
        )
    }

    /// A forgotten named product still counts: it is the household's data too.
    public var needsBackup: Bool {
        summary.needsBackup || !namedProducts.isEmpty
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
    public var cabinet: CabinetContents
    /// The `formatVersion` of the file this was read from (the import preview shows it); ignored when encoding.
    public var sourceFormatVersion: Int

    public init(exportedAt: Date, app: ArchiveApp, cabinet: CabinetContents, sourceFormatVersion: Int = ArchiveFormat.currentVersion) {
        self.exportedAt = exportedAt
        self.app = app
        self.cabinet = cabinet
        self.sourceFormatVersion = sourceFormatVersion
    }
}
