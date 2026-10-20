public enum LocationNameError: Error, Equatable, Sendable {
    case empty
    case tooLong
    case duplicateName

    /// Platform-neutral name, shared with the Android app and `fixtures/location-names.json`.
    public var kind: String {
        switch self {
        case .empty: "empty"
        case .tooLong: "tooLong"
        case .duplicateName: "duplicateName"
        }
    }
}

public enum LocationName {
    /// In Unicode code points: SQLite's `length()` (the v1 `CHECK`, at most 100) counts code points, so this
    /// limit can never fail the database check.
    public static let maxLength = 50

    /// Returns the trimmed name as typed. `existing` lists the other locations' names (not deleted);
    /// `current` is the name of the location being renamed, left out of the duplicate check.
    public static func validate(
        _ raw: String, existing: [String], renaming current: String? = nil
    ) throws(LocationNameError) -> String {
        let name = TextNormalizer.trimmed(raw)
        let key = TextNormalizer.normalize(name)
        guard !key.isEmpty else { throw .empty }
        guard name.unicodeScalars.count <= maxLength else { throw .tooLong }
        var others = existing
        if let current, let index = others.firstIndex(where: { TextNormalizer.compare($0, current) == 0 }) {
            others.remove(at: index)
        }
        if others.contains(where: { TextNormalizer.compare(TextNormalizer.normalize($0), key) == 0 }) {
            throw .duplicateName
        }
        return name
    }
}
