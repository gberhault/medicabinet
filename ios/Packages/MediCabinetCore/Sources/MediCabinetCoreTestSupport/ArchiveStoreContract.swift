import Foundation
import MediCabinetCore

/// Behaviour every `CabinetArchiveStore` implementation must satisfy (the fake, GRDB in the app). Call from any test
/// framework: each check throws `ContractFailure` on violation. The names and their order are listed in
/// `fixtures/archive-store-contract.json`, which the Android contract asserts too, so the two cannot drift.
public enum ArchiveStoreContract {
    /// Makes a new, empty store for one check.
    public typealias Factory = @Sendable () async throws -> any CabinetArchiveStore

    public struct Check: Sendable, CustomStringConvertible {
        public let name: String
        let body: @Sendable (any CabinetArchiveStore) async throws -> Void
        public var description: String { name }

        public func run(_ make: Factory) async throws {
            try await body(try await make())
        }
    }

    private struct HookFailure: Error {}

    public static var checks: [Check] {
        [
            Check(name: "a new store snapshots as empty") { store in
                let snapshot = try await store.snapshot()
                try require(snapshot.locations.isEmpty && snapshot.boxes.isEmpty, "a new store is not empty")
            },
            Check(name: "replace swaps the whole cabinet keeping ids, fields and dates") { store in
                try await store.replace(with: Sample.first) { _ in }
                try require(normalized(try await store.snapshot()) == normalized(Sample.first), "the first cabinet changed")
                try await store.replace(with: Sample.second) { _ in }
                try require(normalized(try await store.snapshot()) == normalized(Sample.second), "the old cabinet was not replaced")
            },
            Check(name: "snapshot includes removed and deleted boxes") { store in
                try await store.replace(with: Sample.first) { _ in }
                let boxes = try await store.snapshot().boxes
                try require(boxes.contains { $0.box.removal != nil && $0.deletedAt == nil }, "a removed box is missing")
                try require(boxes.contains { $0.deletedAt != nil }, "a deleted box is missing")
                try require(boxes.contains { $0.box.removal == nil && $0.deletedAt == nil }, "a box in the cabinet is missing")
            },
            Check(name: "snapshot includes deleted locations") { store in
                try await store.replace(with: Sample.first) { _ in }
                let locations = try await store.snapshot().locations
                try require(locations.contains { $0.deletedAt != nil }, "a deleted location is missing")
                try require(locations.contains { $0.deletedAt == nil }, "a location is missing")
            },
            Check(name: "replace hands the hook exactly the cabinet it replaces") { store in
                try await store.replace(with: Sample.first) { _ in }
                let seen = SnapshotBox()
                try await store.replace(with: Sample.second) { seen.set($0) }
                try require(seen.value.map(normalized) == normalized(Sample.first), "the hook saw another cabinet")
            },
            Check(name: "a throwing hook leaves the cabinet unchanged") { store in
                try await store.replace(with: Sample.first) { _ in }
                try await expectFailure(HookFailure.self) {
                    try await store.replace(with: Sample.second) { _ in throw HookFailure() }
                }
                try require(normalized(try await store.snapshot()) == normalized(Sample.first), "a failed replace changed the cabinet")
            },
            Check(name: "a box in an unknown location is rejected and changes nothing") { store in
                try await store.replace(with: Sample.first) { _ in }
                let lost = ArchivedBox(box: Sample.box(1, in: Sample.unknownLocationID))
                let bad = CabinetContents(locations: Sample.second.locations, boxes: [lost])
                try await expectError(.unknownLocation) { try await store.replace(with: bad) { _ in } }
                try require(normalized(try await store.snapshot()) == normalized(Sample.first), "a rejected replace changed the cabinet")
            },
            Check(name: "a duplicate id is rejected and changes nothing") { store in
                try await store.replace(with: Sample.first) { _ in }
                let twin = Sample.second.boxes[0]
                let bad = CabinetContents(locations: Sample.second.locations, boxes: [twin, twin])
                try await expectError(.duplicate) { try await store.replace(with: bad) { _ in } }
                try require(normalized(try await store.snapshot()) == normalized(Sample.first), "a rejected replace changed the cabinet")
            },
            Check(name: "two default locations are rejected and changes nothing") { store in
                try await store.replace(with: Sample.first) { _ in }
                let defaults = Sample.second.locations.map { archived in
                    let location = archived.location
                    let made = Location(id: location.id, name: location.name, isDefault: true, createdAt: location.createdAt)
                    return ArchivedLocation(location: made)
                }
                let bad = CabinetContents(locations: defaults + [Sample.extraDefault], boxes: [])
                try await expectError(.duplicateDefault) { try await store.replace(with: bad) { _ in } }
                try require(normalized(try await store.snapshot()) == normalized(Sample.first), "a rejected replace changed the cabinet")
            },
            Check(name: "replacing with an empty cabinet empties the store") { store in
                try await store.replace(with: Sample.first) { _ in }
                try await store.replace(with: CabinetContents(locations: [], boxes: [])) { _ in }
                let snapshot = try await store.snapshot()
                try require(snapshot.locations.isEmpty && snapshot.boxes.isEmpty, "the store was not emptied")
            }
        ]
    }

    // MARK: Helpers

    private static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw ContractFailure(description: message) }
    }

    /// Stores return rows in their own order; the contract compares contents.
    private static func normalized(_ snapshot: CabinetContents) -> CabinetContents {
        CabinetContents(
            locations: snapshot.locations.sorted { $0.location.id.uuidString < $1.location.id.uuidString },
            boxes: snapshot.boxes.sorted { $0.box.id.uuidString < $1.box.id.uuidString }
        )
    }

    /// Matches by description rather than `as`: when the app and its test bundle each link a static copy of the core,
    /// the two copies' types are distinct at runtime and dynamic casts between them fail.
    private static func expectError(_ expected: RepositoryError, from work: () async throws -> Void) async throws {
        do {
            try await work()
        } catch {
            if String(reflecting: error) == String(reflecting: expected) { return }
            throw ContractFailure(description: "expected \(expected) but got \(error)")
        }
        throw ContractFailure(description: "expected \(expected) but nothing was thrown")
    }

    private static func expectFailure<Failure: Error>(_ type: Failure.Type, from work: () async throws -> Void) async throws {
        do {
            try await work()
        } catch {
            if String(describing: Swift.type(of: error)) == String(describing: type) { return }
            throw ContractFailure(description: "expected \(type) but got \(error)")
        }
        throw ContractFailure(description: "expected \(type) but nothing was thrown")
    }

    private final class SnapshotBox: @unchecked Sendable {
        private let lock = NSLock()
        private var stored: CabinetContents?

        func set(_ snapshot: CabinetContents) {
            lock.lock()
            defer { lock.unlock() }
            stored = snapshot
        }

        var value: CabinetContents? {
            lock.lock()
            defer { lock.unlock() }
            return stored
        }
    }
}

/// Two small, fixed cabinets with every kind of box.
private enum Sample {
    static let unknownLocationID = UUID(uuidString: "10000009-0000-4000-8000-000000000009") ?? UUID()
    private static let stamp = Date(timeIntervalSince1970: 1_800_000_000.123)

    static func location(_ number: Int, name: String, isDefault: Bool, deleted: Bool = false) -> ArchivedLocation {
        let id = UUID(uuidString: String(format: "1000000%d-0000-4000-8000-00000000000%d", number, number)) ?? UUID()
        let location = Location(id: id, name: name, isDefault: isDefault, sortOrder: number, createdAt: stamp, updatedAt: stamp)
        return ArchivedLocation(location: location, deletedAt: deleted ? stamp : nil)
    }

    static func box(_ number: Int, in locationID: UUID) -> Box {
        let id = UUID(uuidString: String(format: "B%07d-0000-4000-8000-%012d", number, number)) ?? UUID()
        return Box(
            id: id, product: ProductRef(gtin: "03400936404564", name: "Box \(number)"),
            category: CategoryID(rawValue: "pain-fever"), categoryOverridden: true, locationID: locationID,
            expiry: try? ExpiryDate.make(year: 2027, month: number, day: nil), lot: "LOT\(number)", serial: "S\(number)",
            isOpened: true, createdAt: stamp, updatedAt: stamp
        )
    }

    static let extraDefault = location(5, name: "Extra", isDefault: true)

    static let first: CabinetContents = {
        let home = location(1, name: "Home", isDefault: true)
        let car = location(2, name: "Car", isDefault: false)
        let old = location(4, name: "Old cupboard", isDefault: false, deleted: true)
        var removed = box(2, in: old.location.id)
        removed.removal = Box.Removal(reason: .thrownAway, date: stamp)
        return CabinetContents(
            locations: [home, car, old],
            boxes: [
                ArchivedBox(box: box(1, in: home.location.id)), ArchivedBox(box: removed),
                ArchivedBox(box: box(3, in: car.location.id), deletedAt: stamp)
            ]
        )
    }()

    static let second: CabinetContents = {
        let kitchen = location(3, name: "Kitchen", isDefault: true)
        return CabinetContents(locations: [kitchen], boxes: [ArchivedBox(box: box(4, in: kitchen.location.id))])
    }()
}
