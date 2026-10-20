import Foundation

/// One case of `fixtures/expiry-dates.json`, the test vectors shared with the Android app.
public struct ExpiryDateCase: Decodable, Sendable, CustomStringConvertible {
    public struct Expect: Decodable, Sendable {
        public let lastValidDay: Int?
        public let sortKey: String?
        public let monthPrecision: Bool?
        /// `invalidYear` | `invalidMonth` | `invalidDay`
        public let error: String?
    }

    public let name: String
    public let year: Int
    public let month: Int
    public let day: Int?
    public let expect: Expect

    public var description: String { name }
}

public struct ExpiryDateFixture: Decodable, Sendable {
    public let formatVersion: Int
    public let description: String
    public let cases: [ExpiryDateCase]
}

/// One case of `fixtures/categories.json`: a category in display order.
public struct CategoryCase: Decodable, Sendable, CustomStringConvertible {
    public let name: String
    public let assignedByBuild: Bool
    public let userSelectable: Bool

    public var description: String { name }
}

/// One case of `fixtures/reference-lookup.json`: a GTIN, the CIP13 it converts to and the sample database's answer.
public struct ReferenceLookupCase: Decodable, Sendable, CustomStringConvertible {
    public struct Product: Decodable, Sendable, Equatable {
        public let name: String
        public let category: String
    }

    public struct Expect: Decodable, Sendable {
        public let cip13: String?
        public let product: Product?
    }

    public let name: String
    public let gtin: String
    public let expect: Expect

    public var description: String { name }
}

private struct CasesFile<Case: Decodable>: Decodable {
    let cases: [Case]
}

/// The expiry a GS1 fixture case expects: a date, `"missing"`, or `{invalid: kind}`.
public enum GS1ExpectedExpiry: Decodable, Sendable, Equatable {
    case missing
    case date(year: Int, month: Int, day: Int?)
    case invalid(kind: String)

    private enum Keys: String, CodingKey { case year, month, day, invalid }

    public init(from decoder: any Decoder) throws {
        if let text = try? decoder.singleValueContainer().decode(String.self) {
            guard text == "missing" else {
                throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: text))
            }
            self = .missing
            return
        }
        let container = try decoder.container(keyedBy: Keys.self)
        if let kind = try container.decodeIfPresent(String.self, forKey: .invalid) {
            self = .invalid(kind: kind)
        } else {
            self = .date(
                year: try container.decode(Int.self, forKey: .year),
                month: try container.decode(Int.self, forKey: .month),
                day: try container.decodeIfPresent(Int.self, forKey: .day)
            )
        }
    }
}

/// One case of `fixtures/gs1-datamatrix.json`, the payloads shared with the Android app.
public struct GS1Case: Decodable, Sendable, CustomStringConvertible {
    public struct Expect: Decodable, Sendable {
        public let gtin: String?
        /// `dataMatrix` | `ean13`
        public let symbology: String?
        public let expiry: GS1ExpectedExpiry?
        public let lot: String?
        public let serial: String?
        /// `empty` | `tooLong` | `invalidCharacters` | `missingGTIN` | `invalidGTIN`
        public let error: String?
    }

    public let name: String
    public let payload: String
    public let shape: String
    public let expect: Expect

    public var description: String { name }
}

public struct GS1Fixture: Decodable, Sendable {
    public let formatVersion: Int
    public let description: String
    public let cases: [GS1Case]
}

public struct ScanDebounceFrame: Decodable, Sendable {
    public let milliseconds: Int
    public let visible: [String]

    private enum CodingKeys: String, CodingKey {
        case milliseconds = "t"
        case visible
    }
}

public struct ScanDebounceScan: Decodable, Sendable, Equatable {
    public let milliseconds: Int
    public let payload: String

    public init(milliseconds: Int, payload: String) {
        self.milliseconds = milliseconds
        self.payload = payload
    }

    private enum CodingKeys: String, CodingKey {
        case milliseconds = "t"
        case payload
    }
}

/// One case of `fixtures/scan-debounce.json`.
public struct ScanDebounceCase: Decodable, Sendable, CustomStringConvertible {
    public let name: String
    public let rescanGapMs: Int
    public let frames: [ScanDebounceFrame]
    public let expectScans: [ScanDebounceScan]

    public var description: String { name }
}

/// One case of `fixtures/scan-intake.json`.
public struct IntakeCase: Decodable, Sendable, CustomStringConvertible {
    public struct Scan: Decodable, Sendable {
        public let hasSerial: Bool
        /// `known` | `missing` | `invalid`
        public let expiry: String
    }

    public struct Expect: Decodable, Sendable {
        /// `add` | `complete` | `confirmAnother` | `alreadyInCabinet` | `removedEarlier`
        public let decision: String
        public let missing: [String]?
        public let thenMissing: [String]?
        public let expiryUnreadable: Bool?
    }

    public let name: String
    public let scan: Scan
    public let productKnown: Bool
    /// `inCabinet` | `removed` | `nil`
    public let cabinetMatch: String?
    public let sessionHasGTIN: Bool
    public let expect: Expect

    public var description: String { name }
}

/// One case of `fixtures/product-resolution.json`.
public struct ProductResolutionCase: Decodable, Sendable, CustomStringConvertible {
    public struct Reference: Decodable, Sendable {
        public let name: String
        public let category: String?
    }

    public struct Previous: Decodable, Sendable {
        public let name: String
        public let category: String
        public let categoryOverridden: Bool
    }

    public struct Expect: Decodable, Sendable {
        public let name: String?
        public let category: String
        public let categoryOverridden: Bool
    }

    public let name: String
    public let reference: Reference?
    public let previousBox: Previous?
    public let expect: Expect

    public var description: String { name }
}

private struct CaseList<Element: Decodable>: Decodable {
    let cases: [Element]
}

/// One case of `fixtures/archive-code-points.json`.
public struct ArchiveCodePointCase: Decodable, Sendable, CustomStringConvertible {
    public let name: String
    public let text: String
    public let codePoints: Int

    public var description: String { name }
}

public struct ArchiveCodePointFixture: Decodable, Sendable {
    public let formatVersion: Int
    public let description: String
    public let cases: [ArchiveCodePointCase]
}

/// A fixture file that is only a list of named cases.
private struct NamedCases: Decodable {
    let cases: [NamedCase]
}

private struct NamedCase: Decodable {
    let name: String
}

/// Loads the repository's shared fixtures. Set `MEDICABINET_FIXTURES_DIR` to point at another
/// folder (the gate tests use this to prove a wrong fixture fails the suite).
public enum SharedFixtures {
    public static var directory: URL {
        if let override = ProcessInfo.processInfo.environment["MEDICABINET_FIXTURES_DIR"] {
            return URL(fileURLWithPath: override, isDirectory: true)
        }
        // …/ios/Packages/MediCabinetCore/Sources/MediCabinetCoreTestSupport/SharedFixtures.swift → repository root
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<6 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("fixtures", isDirectory: true)
    }

    public static func data(_ name: String) throws -> Data {
        try Data(contentsOf: directory.appendingPathComponent(name))
    }

    public static func expiryDates() throws -> ExpiryDateFixture {
        try JSONDecoder().decode(ExpiryDateFixture.self, from: data("expiry-dates.json"))
    }

    public static func gs1Datamatrix() throws -> GS1Fixture {
        try JSONDecoder().decode(GS1Fixture.self, from: data("gs1-datamatrix.json"))
    }

    /// Names of the archive store contract checks, in order (`fixtures/archive-store-contract.json`).
    public static func archiveStoreContractCheckNames() throws -> [String] {
        try JSONDecoder().decode(NamedCases.self, from: data("archive-store-contract.json")).cases.map(\.name)
    }

    public static func archiveCodePoints() throws -> ArchiveCodePointFixture {
        try JSONDecoder().decode(ArchiveCodePointFixture.self, from: data("archive-code-points.json"))
    }

    public static func archiveCodePointCases() -> [ArchiveCodePointCase] {
        (try? archiveCodePoints().cases) ?? []
    }

    /// Names of the repository contract checks, in order (`fixtures/repository-contract.json`).
    public static func repositoryContractCheckNames() throws -> [String] {
        try JSONDecoder().decode(NamedCases.self, from: data("repository-contract.json")).cases.map(\.name)
    }

    /// For `@Test(arguments:)`, which cannot throw. A missing file yields no cases, so every
    /// fixture-driven suite also has a test asserting the file loads (which fails loudly).
    public static func expiryDateCases() -> [ExpiryDateCase] {
        (try? expiryDates().cases) ?? []
    }

    private static func cases<Element: Decodable>(_ file: String, as type: Element.Type) -> [Element] {
        guard let data = try? data(file) else { return [] }
        return (try? JSONDecoder().decode(CaseList<Element>.self, from: data).cases) ?? []
    }

    public static func scanDebounceCases() -> [ScanDebounceCase] { cases("scan-debounce.json", as: ScanDebounceCase.self) }

    public static func intakeCases() -> [IntakeCase] { cases("scan-intake.json", as: IntakeCase.self) }

    public static func productResolutionCases() -> [ProductResolutionCase] {
        cases("product-resolution.json", as: ProductResolutionCase.self)
    }

    public static func gs1Cases() -> [GS1Case] {
        (try? gs1Datamatrix().cases) ?? []
    }

    /// The taxonomy in display order (`fixtures/categories.json`).
    public static func categories() throws -> [CategoryCase] {
        try JSONDecoder().decode(CasesFile<CategoryCase>.self, from: data("categories.json")).cases
    }

    public static func referenceLookup() throws -> [ReferenceLookupCase] {
        try JSONDecoder().decode(CasesFile<ReferenceLookupCase>.self, from: data("reference-lookup.json")).cases
    }

    public static func referenceLookupCases() -> [ReferenceLookupCase] {
        (try? referenceLookup()) ?? []
    }

    /// The sample reference database the lookup cases are answered from (`fixtures/reference-sample.sqlite`).
    public static var referenceSampleURL: URL {
        directory.appendingPathComponent("reference-sample.sqlite")
    }
}
