import Foundation

/// How an object of a known shape is read: one entry per key, each reading its value into a draft.
/// Every key is required (an absent value is an explicit `null`), unknown and duplicate keys are
/// refused, and the first missing key is named once the object ends.
///
/// The closure is plain `throws`: function values with a typed `throws` need the iOS 18 runtime and the
/// package supports iOS 17. `ArchiveObjectReading.readObject` turns whatever it throws back into an `ArchiveError`.
struct ObjectField<Draft>: Sendable {
    let key: String
    let read: @Sendable (inout Draft, inout JSONReader, FieldPath) throws -> Void
}

enum ArchiveObjectReading {
    /// Reads the object at the reader's position into `draft`. `path` is the object's own path (`boxes[3]`,
    /// empty for the root); `opened` is true when its `{` and first members were already consumed.
    static func readObject<Draft>(
        _ reader: inout JSONReader, path: String, into draft: inout Draft, fields: [ObjectField<Draft>], opened: Bool = false
    ) throws(ArchiveError) {
        if !opened {
            guard try reader.peek() == .object else { throw ArchiveError(.invalidStructure, path: path) }
            try reader.beginObject()
        }
        // Which fields were read, one bit each (an object has far fewer than 64).
        var seen: UInt64 = 0
        var isFirst = !opened
        while let key = try reader.nextKey(isFirst: isFirst) {
            isFirst = false
            guard let index = fields.firstIndex(where: { $0.key == key }), seen & (1 << UInt64(index)) == 0 else {
                throw ArchiveError(.invalidStructure, path: FieldPath(path, key).text)
            }
            seen |= 1 << UInt64(index)
            do {
                try fields[index].read(&draft, &reader, FieldPath(path, key))
            } catch {
                throw archiveError(error, path: FieldPath(path, key))
            }
        }
        if let missing = fields.indices.first(where: { seen & (1 << UInt64($0)) == 0 }) {
            throw ArchiveError(.invalidStructure, path: FieldPath(path, fields[missing].key).text)
        }
    }

    /// A field reader only throws `ArchiveError`; anything else would be a bug, reported as a structure error at that field.
    static func archiveError(_ error: any Error, path: FieldPath) -> ArchiveError {
        (error as? ArchiveError) ?? ArchiveError(.invalidStructure, path: path.text)
    }

    // MARK: Lists

    /// Walks a JSON array: `next` yields the index of each element (the caller reads it), and fails with
    /// `.tooManyItems` as soon as an item beyond the limit starts.
    struct ListCursor {
        let path: String
        let limit: Int
        private(set) var count = 0

        init(_ reader: inout JSONReader, path: String, limit: Int) throws(ArchiveError) {
            guard try reader.peek() == .array else { throw ArchiveError(.invalidStructure, path: path) }
            try reader.beginArray()
            self.path = path
            self.limit = limit
        }

        mutating func next(_ reader: inout JSONReader) throws(ArchiveError) -> Int? {
            guard try reader.hasNextElement(isFirst: count == 0) else { return nil }
            if count >= limit { throw ArchiveError(.tooManyItems, path: path) }
            count += 1
            return count - 1
        }
    }

    // MARK: Scalars

    static func readUUID(_ reader: inout JSONReader, _ field: FieldPath) throws(ArchiveError) -> UUID {
        let text = try reader.readString(field)
        guard isCanonicalUUID(text), let uuid = UUID(uuidString: text) else {
            throw ArchiveError(.invalidValue, path: field.text)
        }
        return uuid
    }

    static func readDate(_ reader: inout JSONReader, _ field: FieldPath) throws(ArchiveError) -> Date {
        guard let date = ArchiveDates.parse(try reader.readString(field)) else {
            throw ArchiveError(.invalidValue, path: field.text)
        }
        return date
    }

    /// A date, or `nil` for a JSON `null`.
    static func readOptionalDate(_ reader: inout JSONReader, _ field: FieldPath) throws(ArchiveError) -> Date? {
        if try reader.readNullIfPresent() { return nil }
        return try readDate(&reader, field)
    }

    /// An integer that fits 32 bits (the database's `Int`, and Kotlin's).
    static func readInt32(_ reader: inout JSONReader, _ field: FieldPath) throws(ArchiveError) -> Int {
        let value = try reader.readInteger(field)
        guard let narrowed = Int32(exactly: value) else { throw ArchiveError(.invalidValue, path: field.text) }
        return Int(narrowed)
    }

    /// 8-4-4-4-12 hexadecimal digits, either case: stricter than `UUID(uuidString:)` on some platforms and
    /// than Java's `UUID.fromString` on all of them, so both platforms accept exactly the same text.
    private static func isCanonicalUUID(_ text: String) -> Bool {
        let bytes = Array(text.utf8)
        guard bytes.count == 36 else { return false }
        return bytes.enumerated().allSatisfy { index, byte in
            if [8, 13, 18, 23].contains(index) { return byte == UInt8(ascii: "-") }
            return (0x30...0x39).contains(byte) || (0x41...0x46).contains(byte) || (0x61...0x66).contains(byte)
        }
    }
}
