/// Why an archive was refused. The raw values are the platform-neutral names shared with the
/// Android app (`fixtures/README.md`, `fixtures/archive-cases.json`).
public enum ArchiveErrorKind: String, Sendable, CaseIterable {
    case tooLarge
    case notJSON
    case notArchive
    case unsupportedVersion
    case newerVersion
    case invalidStructure
    case invalidValue
    case tooManyItems
    case duplicateId
    case unknownLocation
    case defaultLocation
    case duplicateSerial
    case duplicateNamedProduct
}

/// A refused archive. `path` names the field (`boxes[12].expiry`): keys and list indexes only,
/// never a value, so an error can be logged without leaking the cabinet. `nil` when no single
/// field is at fault (syntax, size, version).
public struct ArchiveError: Error, Equatable, Sendable, CustomStringConvertible {
    public let kind: ArchiveErrorKind
    public let path: String?

    public init(_ kind: ArchiveErrorKind, path: String? = nil) {
        self.kind = kind
        self.path = path
    }

    public var description: String {
        guard let path else { return kind.rawValue }
        return "\(kind.rawValue) at \(path)"
    }
}

/// A field's place in the file: the enclosing object's path (`boxes[3]`) and the key. Cheap to
/// build; the text is only made when an error is thrown.
struct FieldPath: Sendable {
    let prefix: String
    let key: String

    init(_ prefix: String, _ key: String) {
        self.prefix = prefix
        self.key = key
    }

    /// A field of the root object.
    init(_ key: String) {
        self.init("", key)
    }

    var text: String { prefix.isEmpty ? key : prefix + "." + key }
}
