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

    /// Names of the repository contract checks, in order (`fixtures/repository-contract.json`).
    public static func repositoryContractCheckNames() throws -> [String] {
        try JSONDecoder().decode(NamedCases.self, from: data("repository-contract.json")).cases.map(\.name)
    }

    /// For `@Test(arguments:)`, which cannot throw. A missing file yields no cases, so every
    /// fixture-driven suite also has a test asserting the file loads (which fails loudly).
    public static func expiryDateCases() -> [ExpiryDateCase] {
        (try? expiryDates().cases) ?? []
    }
}
