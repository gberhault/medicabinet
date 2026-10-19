import Foundation
import MediCabinetCore

/// An expiry as written in the shared fixtures (`day` null = month precision).
public struct FixtureExpiry: Decodable, Sendable {
    public let year: Int
    public let month: Int
    public let day: Int?

    public func expiryDate() throws -> ExpiryDate {
        try ExpiryDate.make(year: year, month: month, day: day)
    }
}

/// One case of `fixtures/expiry-status.json` → `cases`.
public struct ExpiryStatusCase: Decodable, Sendable, CustomStringConvertible {
    public struct Expect: Decodable, Sendable {
        public let status: String
        public let daysLeft: Int?
    }

    public let name: String
    public let today: String
    public let expiry: FixtureExpiry?
    public let windowDays: Int
    public let expect: Expect

    public var description: String { name }
}

/// One case of `fixtures/expiry-status.json` → `todayCases`.
public struct TodayCase: Decodable, Sendable, CustomStringConvertible {
    public let name: String
    public let instant: String
    public let timeZone: String
    public let expect: String
    public let minutesSinceMidnight: Int

    public var description: String { name }

    public func date() -> Date? { ISO8601DateFormatter().date(from: instant) }
}

public struct ExpiryStatusFixture: Decodable, Sendable {
    public let formatVersion: Int
    public let description: String
    public let cases: [ExpiryStatusCase]
    public let todayCases: [TodayCase]
}

extension SharedFixtures {
    public static func expiryStatus() throws -> ExpiryStatusFixture {
        try JSONDecoder().decode(ExpiryStatusFixture.self, from: data("expiry-status.json"))
    }

    public static func expiryStatusCases() -> [ExpiryStatusCase] {
        (try? expiryStatus().cases) ?? []
    }

    public static func todayCases() -> [TodayCase] {
        (try? expiryStatus().todayCases) ?? []
    }
}
