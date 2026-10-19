import Foundation

/// Reads an archive of any `formatVersion` this app knows (design §2.5, §2.7). The file is untrusted:
/// whatever the bytes, the result is an `Archive` that passed `ArchiveValidator`, or an `ArchiveError`.
public enum ArchiveDecoder {
    /// One reader per `formatVersion`, index 0 being version 1. A reader is never edited once shipped (F9);
    /// a new version appends its reader, and migrates what it reads into the current model there.
    static let readers: [any ArchiveVersionReader.Type] = [ArchiveV1Reader.self]

    public static func decode(_ bytes: [UInt8], limits: ArchiveLimits = .default) throws(ArchiveError) -> Archive {
        guard bytes.count <= limits.maxFileBytes else { throw ArchiveError(.tooLarge) }
        var reader = try JSONReader(bytes, maxStringBytes: limits.maxStringBytes)
        let version = try readHeader(&reader)
        let body = try readers[version - 1].read(&reader, limits: limits)
        try reader.finish()
        let archive = Archive(
            exportedAt: body.exportedAt, app: body.app,
            cabinet: CabinetSnapshot(locations: body.locations, boxes: body.boxes), sourceFormatVersion: version
        )
        try ArchiveValidator.validate(archive, limits: limits)
        return archive
    }

    /// The first two members must be `archive` = "medicabinet" and `formatVersion`; returns the version,
    /// which is one this app can read.
    private static func readHeader(_ reader: inout JSONReader) throws(ArchiveError) -> Int {
        guard try reader.peek() == .object else { throw ArchiveError(.notArchive) }
        try reader.beginObject()
        guard try reader.nextKey(isFirst: true) == "archive", try readMarker(&reader) else { throw ArchiveError(.notArchive) }
        guard try reader.nextKey(isFirst: false) == "formatVersion", let version = try readVersion(&reader) else {
            throw ArchiveError(.notArchive)
        }
        guard version >= 1 else { throw ArchiveError(.unsupportedVersion) }
        guard version <= readers.count else { throw ArchiveError(.newerVersion) }
        return Int(version)
    }

    private static func readMarker(_ reader: inout JSONReader) throws(ArchiveError) -> Bool {
        guard try reader.peek() == .string else { return false }
        return try reader.readString(FieldPath("", "archive")) == ArchiveFormat.magic
    }

    /// The version, or `nil` when it is not an integer Int64 can hold.
    private static func readVersion(_ reader: inout JSONReader) throws(ArchiveError) -> Int64? {
        guard try reader.peek() == .number else { return nil }
        return try? reader.readInteger(FieldPath("", "formatVersion"))
    }
}
