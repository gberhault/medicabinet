import Foundation
import MediCabinetCore

public struct ContractFailure: Error, CustomStringConvertible {
    public let description: String
}

/// Behaviour every `LocationRepository` + `BoxRepository` + `NamedProductRepository` implementation must satisfy.
/// Call from any test framework: each check throws `ContractFailure` on violation.
/// Dates used are whole seconds so stores with millisecond precision round-trip them exactly.
public enum RepositoryContract {
    /// The three ports of one store; the named products and the boxes share their state.
    public struct Stores: Sendable {
        public let locations: any LocationRepository
        public let boxes: any BoxRepository
        public let named: any NamedProductRepository

        public init(
            locations: any LocationRepository, boxes: any BoxRepository, named: any NamedProductRepository
        ) {
            self.locations = locations
            self.boxes = boxes
            self.named = named
        }
    }

    public typealias Factory = @Sendable (MutableClock) async throws -> Stores

    public struct Check: Sendable, CustomStringConvertible {
        public let name: String
        let body: @Sendable (Fixture) async throws -> Void
        public var description: String { name }

        public func run(_ make: Factory) async throws {
            let clock = MutableClock(Fixture.epoch)
            let stores = try await make(clock)
            try await body(Fixture(clock: clock, locations: stores.locations, boxes: stores.boxes, named: stores.named))
        }
    }

    public struct Fixture: Sendable {
        static let epoch = Date(timeIntervalSince1970: 1_800_000_000)
        let clock: MutableClock
        let locations: any LocationRepository
        let boxes: any BoxRepository
        let named: any NamedProductRepository

        func location(_ name: String = "Bathroom", isDefault: Bool = false, sortOrder: Int = 0) -> Location {
            Location(name: name, isDefault: isDefault, sortOrder: sortOrder, createdAt: clock.now)
        }

        /// Every box gets its own serial unless one is given: the same GTIN and serial cannot exist twice.
        func box(
            in location: Location, name: String = "Doliprane", expiry: ExpiryDate? = nil,
            gtin: String = "03400930000001", serial: String? = String(UUID().uuidString.prefix(12))
        ) -> Box {
            Box(product: ProductRef(gtin: gtin, name: name), category: CategoryID(rawValue: "pain-fever"),
                locationID: location.id, expiry: expiry, lot: "L1", serial: serial, createdAt: clock.now)
        }
    }

    public static var checks: [Check] { locationChecks + boxChecks + namedProductChecks }

    static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw ContractFailure(description: message) }
    }

    /// Matches by type and case description rather than `as RepositoryError`: when the app and
    /// its test bundle each link a static copy of the core, the two copies' types are distinct
    /// at runtime and dynamic casts between them fail.
    static func expect(_ expected: RepositoryError, from work: () async throws -> Void) async throws {
        do {
            try await work()
        } catch {
            if String(reflecting: error) == String(reflecting: expected) { return }
            throw ContractFailure(description: "expected \(expected) but got \(error)")
        }
        throw ContractFailure(description: "expected \(expected) but nothing was thrown")
    }

    private static func expectAnyError(from work: () async throws -> Void) async throws {
        do { try await work() } catch { return }
        throw ContractFailure(description: "expected an error but nothing was thrown")
    }

    private static var locationChecks: [Check] {
        [
            Check(name: "a new store has no locations and no default") { env in
                try require(try await env.locations.all().isEmpty, "all() should be empty")
                try require(try await env.locations.defaultLocation() == nil, "defaultLocation() should be nil")
            },
            Check(name: "locations are returned by sortOrder then name") { env in
                try await env.locations.insert(env.location("B", sortOrder: 1))
                try await env.locations.insert(env.location("Z", sortOrder: 0))
                try await env.locations.insert(env.location("A", sortOrder: 1))
                try require(try await env.locations.all().map(\.name) == ["Z", "A", "B"], "wrong order")
            },
            Check(name: "ensureDefault creates the default once and is idempotent") { env in
                let first = try await env.locations.ensureDefault(named: "Cabinet")
                let second = try await env.locations.ensureDefault(named: "Other name")
                try require(first.isDefault && first.name == "Cabinet", "first should be the default")
                try require(second.id == first.id && second.name == "Cabinet", "second call must return the same default")
                try require(try await env.locations.all().count == 1, "only one location expected")
                try require(try await env.locations.defaultLocation()?.id == first.id, "defaultLocation() mismatch")
            },
            Check(name: "ensureDefault creates a default next to existing non-default locations") { env in
                try await env.locations.insert(env.location("Kitchen"))
                let created = try await env.locations.ensureDefault(named: "Cabinet")
                try require(created.isDefault, "created location should be the default")
                try require(try await env.locations.all().count == 2, "two locations expected")
            },
            Check(name: "a second default location is rejected") { env in
                try await env.locations.insert(env.location("A", isDefault: true))
                try await expectAnyError { try await env.locations.insert(env.location("B", isDefault: true)) }
                try require(try await env.locations.all().count == 1, "rejected insert must not persist")
            },
            Check(name: "inserting a known location id is rejected") { env in
                let location = env.location()
                try await env.locations.insert(location)
                try await expectAnyError { try await env.locations.insert(location) }
            }
        ]
    }

    private static var boxChecks: [Check] {
        [
            Check(name: "a box round-trips with a day-precise expiry") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let box = env.box(in: location, expiry: try ExpiryDate.make(year: 2027, month: 3, day: 15))
                try await env.boxes.insert(box)
                try require(try await env.boxes.box(id: box.id) == box, "stored box differs from inserted")
            },
            Check(name: "month precision and unknown expiry round-trip distinctly") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let monthly = env.box(in: location, name: "M", expiry: try ExpiryDate.make(year: 2028, month: 2, day: nil))
                let unknown = env.box(in: location, name: "U", expiry: nil)
                try await env.boxes.insert(monthly)
                try await env.boxes.insert(unknown)
                try require(try await env.boxes.box(id: monthly.id)?.expiry?.isMonthPrecision == true, "month precision lost")
                try require(try await env.boxes.box(id: unknown.id)?.expiry == nil, "unknown expiry must stay nil")
            },
            Check(name: "markRemoved hides a box from activeBoxes but keeps the record") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let box = env.box(in: location)
                try await env.boxes.insert(box)
                env.clock.advance(by: 60)
                let removedAt = env.clock.now
                try await env.boxes.markRemoved(id: box.id, reason: .usedUp, at: removedAt)
                try require(try await env.boxes.activeBoxes().isEmpty, "removed box must not be active")
                let stored = try await env.boxes.box(id: box.id)
                try require(stored?.removal == Box.Removal(reason: .usedUp, date: removedAt), "removal not recorded")
                try require(stored?.updatedAt == removedAt, "updatedAt must come from the clock")
            },
            Check(name: "undoRemoval restores a removed box") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let box = env.box(in: location)
                try await env.boxes.insert(box)
                try await env.boxes.markRemoved(id: box.id, reason: .thrownAway, at: env.clock.now)
                env.clock.advance(by: 30)
                try await env.boxes.undoRemoval(id: box.id)
                try require(try await env.boxes.activeBoxes().map(\.id) == [box.id], "box should be active again")
                try require(try await env.boxes.box(id: box.id)?.removal == nil, "removal should be cleared")
                try require(try await env.boxes.box(id: box.id)?.updatedAt == env.clock.now, "updatedAt must be bumped")
            },
            Check(name: "update changes fields, bumps updatedAt, keeps createdAt and removal") { env in
                let location = env.location()
                try await env.locations.insert(location)
                var box = env.box(in: location)
                try await env.boxes.insert(box)
                env.clock.advance(by: 120)
                box.isOpened = true
                box.category = CategoryID(rawValue: "cold-ent")
                box.categoryOverridden = true
                box.expiry = try ExpiryDate.make(year: 2029, month: 1, day: 31)
                try await env.boxes.update(box)
                let stored = try await env.boxes.box(id: box.id)
                try require(stored?.isOpened == true && stored?.categoryOverridden == true, "fields not updated")
                try require(stored?.category.rawValue == "cold-ent" && stored?.expiry == box.expiry, "fields not updated")
                try require(stored?.updatedAt == env.clock.now, "updatedAt must be bumped")
                try require(stored?.createdAt == box.createdAt, "createdAt must be preserved")
            },
            Check(name: "operations on an unknown box throw notFound") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let ghost = env.box(in: location)
                try await expect(.notFound) { try await env.boxes.update(ghost) }
                try await expect(.notFound) { try await env.boxes.markRemoved(id: ghost.id, reason: .usedUp, at: env.clock.now) }
                try await expect(.notFound) { try await env.boxes.undoRemoval(id: ghost.id) }
                try require(try await env.boxes.box(id: ghost.id) == nil, "unknown id must return nil")
            },
            Check(name: "a box in an unknown location or with a known id is rejected") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let orphan = env.box(in: env.location("Ghost"))
                try await expect(.unknownLocation) { try await env.boxes.insert(orphan) }
                let box = env.box(in: location)
                try await env.boxes.insert(box)
                try await expect(.duplicate) { try await env.boxes.insert(box) }
                try require(try await env.boxes.activeBoxes().count == 1, "rejected inserts must not persist")
            }
        ] + deletionChecks + cabinetChecks + locationManagementChecks
    }
}
