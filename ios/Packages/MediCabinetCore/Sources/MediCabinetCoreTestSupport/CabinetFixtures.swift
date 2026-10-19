import Foundation

/// One case of `fixtures/text-normalization.json`: `kind` is `normalize` or `match`.
public struct TextNormalizationCase: Decodable, Sendable, CustomStringConvertible {
    public let name: String
    public let kind: String
    public let input: String?
    public let normalized: String?
    public let text: String?
    public let query: String?
    public let matches: Bool?

    public var description: String { name }
}

/// One case of `fixtures/location-names.json`.
public struct LocationNameCase: Decodable, Sendable, CustomStringConvertible {
    public struct Expect: Decodable, Sendable {
        public let name: String?
        /// `empty` | `tooLong` | `duplicateName`
        public let error: String?
    }

    public let name: String
    public let input: String
    public let existing: [String]
    public let renaming: String?
    public let expect: Expect

    public var description: String { name }
}

private struct CabinetCaseList<Element: Decodable>: Decodable {
    let formatVersion: Int
    let cases: [Element]
}

/// Loaders of the cabinet-inventory fixtures. Kept apart from `SharedFixtures` so that file stays small.
extension SharedFixtures {
    public static func textNormalization() throws -> (formatVersion: Int, cases: [TextNormalizationCase]) {
        let list = try JSONDecoder().decode(CabinetCaseList<TextNormalizationCase>.self, from: data("text-normalization.json"))
        return (list.formatVersion, list.cases)
    }

    public static func locationNames() throws -> (formatVersion: Int, cases: [LocationNameCase]) {
        let list = try JSONDecoder().decode(CabinetCaseList<LocationNameCase>.self, from: data("location-names.json"))
        return (list.formatVersion, list.cases)
    }

    /// For `@Test(arguments:)`, which cannot throw: a missing file yields no cases, and each suite also
    /// has a test asserting the file loads.
    public static func textNormalizationCases() -> [TextNormalizationCase] {
        (try? textNormalization().cases) ?? []
    }

    public static func locationNameCases() -> [LocationNameCase] {
        (try? locationNames().cases) ?? []
    }
}
