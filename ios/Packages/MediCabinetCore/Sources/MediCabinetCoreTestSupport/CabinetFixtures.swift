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

/// One case of `fixtures/short-names.json`.
public struct ShortNameCase: Decodable, Sendable, CustomStringConvertible {
    public let name: String
    public let input: String
    public let expect: String

    public var description: String { name }
}

/// One case of `fixtures/leaflet-links.json`: the reference product (`nil` = unknown code) and the address, or `nil`.
public struct LeafletLinkCase: Decodable, Sendable, CustomStringConvertible {
    public struct Product: Decodable, Sendable {
        public let cis: String?
        public let isWithdrawn: Bool
    }

    public let name: String
    public let product: Product?
    public let expect: String?

    public var description: String { name }
}

/// One case of `fixtures/category-order.json`.
public struct CategoryOrderCase: Decodable, Sendable, CustomStringConvertible {
    public let name: String
    public let labels: [String: String]
    public let expect: [String]

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
        public let displayName: String
        public let boxes: [String]
        public let status: String
        public let earliestExpiry: FixtureExpiry?

        public init(name: String, displayName: String, boxes: [String], status: String, earliestExpiry: FixtureExpiry?) {
            self.name = name
            self.displayName = displayName
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

/// One case of `fixtures/scan-removal.json`.
public struct ScanRemovalCase: Decodable, Sendable, CustomStringConvertible {
    public struct Scan: Decodable, Sendable {
        public let gtin: String
        public let serial: String?
        public let expiry: CabinetRowsCase.FixtureExpiry?
    }

    public struct FixtureBox: Decodable, Sendable {
        public let id: String
        public let gtin: String
        public let serial: String?
        public let expiry: CabinetRowsCase.FixtureExpiry?
        /// `inCabinet` | `removed` | `deleted`
        public let state: String
        public let createdOrder: Int
    }

    public struct Expect: Decodable, Sendable {
        /// `remove` | `choose` | `alreadyRemoved` | `notInCabinet`
        public let decision: String
        public let box: String?
        public let candidates: [String]?
    }

    public let name: String
    public let scan: Scan
    public let cabinet: [FixtureBox]
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

    public static func shortNames() throws -> (formatVersion: Int, cases: [ShortNameCase]) {
        let list = try JSONDecoder().decode(CabinetCaseList<ShortNameCase>.self, from: data("short-names.json"))
        return (list.formatVersion, list.cases)
    }

    public static func leafletLinks() throws -> (formatVersion: Int, cases: [LeafletLinkCase]) {
        let list = try JSONDecoder().decode(CabinetCaseList<LeafletLinkCase>.self, from: data("leaflet-links.json"))
        return (list.formatVersion, list.cases)
    }

    public static func leafletLinkCases() -> [LeafletLinkCase] {
        (try? leafletLinks().cases) ?? []
    }

    public static func categoryOrder() throws -> (formatVersion: Int, cases: [CategoryOrderCase]) {
        let list = try JSONDecoder().decode(CabinetCaseList<CategoryOrderCase>.self, from: data("category-order.json"))
        return (list.formatVersion, list.cases)
    }

    public static func categoryOrderCases() -> [CategoryOrderCase] {
        (try? categoryOrder().cases) ?? []
    }

    public static func shortNameCases() -> [ShortNameCase] {
        (try? shortNames().cases) ?? []
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

    public static func scanRemoval() throws -> (formatVersion: Int, cases: [ScanRemovalCase]) {
        let list = try JSONDecoder().decode(CabinetCaseList<ScanRemovalCase>.self, from: data("scan-removal.json"))
        return (list.formatVersion, list.cases)
    }

    public static func scanRemovalCases() -> [ScanRemovalCase] {
        (try? scanRemoval().cases) ?? []
    }
}
