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

public enum FixtureError: Error {
    case invalidID(String)
}

/// A box as written in `fixtures/expiring-overview.json`.
public struct FixtureBox: Decodable, Sendable {
    public let id: String
    public let name: String
    public let expiry: FixtureExpiry?
    public let inCabinet: Bool

    public func box(location: UUID) throws -> Box {
        guard let uuid = UUID(uuidString: id) else { throw FixtureError.invalidID(id) }
        let moment = Date(timeIntervalSince1970: 0)
        return Box(
            id: uuid,
            product: ProductRef(name: name),
            locationID: location,
            expiry: try expiry.map { try $0.expiryDate() },
            createdAt: moment,
            removal: inCabinet ? nil : Box.Removal(reason: .thrownAway, date: moment)
        )
    }
}

/// One case of `fixtures/expiring-overview.json`.
public struct OverviewCase: Decodable, Sendable, CustomStringConvertible {
    public struct Expect: Decodable, Sendable {
        public let expired: [String]
        public let expiringSoon: [String]
        public let unknown: [String]
        public let badge: Int
        public let isEmpty: Bool
    }

    public let name: String
    public let today: String
    public let windowDays: Int
    public let boxes: [FixtureBox]
    public let expect: Expect

    public var description: String { name }
}

public struct OverviewFixture: Decodable, Sendable {
    public let formatVersion: Int
    public let description: String
    public let cases: [OverviewCase]
}

/// A box as written in `fixtures/expiry-digest.json` (only what the digest looks at).
public struct DigestBox: Decodable, Sendable {
    public let expiry: FixtureExpiry?
    public let inCabinet: Bool

    public func box(location: UUID) throws -> Box {
        let moment = Date(timeIntervalSince1970: 0)
        return Box(
            product: ProductRef(name: "Box"),
            locationID: location,
            expiry: try expiry.map { try $0.expiryDate() },
            createdAt: moment,
            removal: inCabinet ? nil : Box.Removal(reason: .usedUp, date: moment)
        )
    }
}

/// One case of `fixtures/expiry-digest.json` → `cases`.
public struct DigestCountCase: Decodable, Sendable, CustomStringConvertible {
    public struct Expect: Decodable, Sendable {
        public let expired: Int
        public let expiringSoon: Int
    }

    public let name: String
    public let digestDay: String
    public let windowDays: Int
    public let boxes: [DigestBox]
    /// `nil`: nothing is sent.
    public let expect: Expect?

    public var description: String { name }
}

/// One case of `fixtures/expiry-digest.json` → `scheduleCases`.
public struct DigestScheduleCase: Decodable, Sendable, CustomStringConvertible {
    public struct Expect: Decodable, Sendable {
        public let upcoming: [String]
        public let due: String
    }

    public let name: String
    public let today: String
    public let minutesSinceMidnight: Int
    public let count: Int
    public let expect: Expect

    public var description: String { name }
}

public struct DigestFixture: Decodable, Sendable {
    public let formatVersion: Int
    public let description: String
    public let cases: [DigestCountCase]
    public let scheduleCases: [DigestScheduleCase]
}

extension SharedFixtures {
    public static func expiringOverview() throws -> OverviewFixture {
        try JSONDecoder().decode(OverviewFixture.self, from: data("expiring-overview.json"))
    }

    public static func overviewCases() -> [OverviewCase] {
        (try? expiringOverview().cases) ?? []
    }

    public static func expiryDigest() throws -> DigestFixture {
        try JSONDecoder().decode(DigestFixture.self, from: data("expiry-digest.json"))
    }

    public static func digestCountCases() -> [DigestCountCase] {
        (try? expiryDigest().cases) ?? []
    }

    public static func digestScheduleCases() -> [DigestScheduleCase] {
        (try? expiryDigest().scheduleCases) ?? []
    }

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
