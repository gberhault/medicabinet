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

/// One case of `fixtures/cabinet-rows.json`.
public struct CabinetRowsCase: Decodable, Sendable, CustomStringConvertible {
    public struct FixtureExpiry: Decodable, Hashable, Sendable {
        public let year: Int
        public let month: Int
        public let day: Int?

        public init(year: Int, month: Int, day: Int?) {
            self.year = year
            self.month = month
            self.day = day
        }
    }

    public struct FixtureBox: Decodable, Sendable {
        public let id: String
        public let gtin: String?
        public let name: String
        public let category: String
        public let location: String
        public let expiry: FixtureExpiry?
        /// `expired` | `expiringSoon` | `ok` | `unknown`
        public let status: String
        public let createdOrder: Int
    }

    public struct FixtureFilter: Decodable, Sendable {
        public let query: String
        public let location: String?
        public let status: String?
    }

    public struct FixtureRow: Decodable, Hashable, Sendable {
        public let name: String
        public let boxes: [String]
        public let status: String
        public let earliestExpiry: FixtureExpiry?

        public init(name: String, boxes: [String], status: String, earliestExpiry: FixtureExpiry?) {
            self.name = name
            self.boxes = boxes
            self.status = status
            self.earliestExpiry = earliestExpiry
        }
    }

    public struct FixtureSection: Decodable, Hashable, Sendable {
        public let category: String
        public let rows: [FixtureRow]

        public init(category: String, rows: [FixtureRow]) {
            self.category = category
            self.rows = rows
        }
    }

    public struct Expect: Decodable, Sendable {
        public let boxCount: Int
        public let sections: [FixtureSection]
    }

    public let name: String
    public let categoryOrder: [String]
    public let boxes: [FixtureBox]
    public let filter: FixtureFilter
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

    public static func cabinetRows() throws -> (formatVersion: Int, cases: [CabinetRowsCase]) {
        let list = try JSONDecoder().decode(CabinetCaseList<CabinetRowsCase>.self, from: data("cabinet-rows.json"))
        return (list.formatVersion, list.cases)
    }

    public static func cabinetRowsCases() -> [CabinetRowsCase] {
        (try? cabinetRows().cases) ?? []
    }
}
