import Foundation
import MediCabinetCore

/// Checks for managing locations: create, rename, reorder, default, tombstone, and the observation streams.
extension RepositoryContract {
    /// Latest value of an observation stream, with a bounded wait for a condition.
    actor Observed<Value: Sendable> {
        private var latest: Value?

        func record(_ value: Value) { latest = value }

        func wait(_ what: String, until condition: @Sendable (Value) -> Bool) async throws {
            let start = ContinuousClock.now
            while true {
                if let latest, condition(latest) { return }
                if ContinuousClock.now - start > .seconds(5) {
                    throw ContractFailure(description: "timed out waiting for: \(what)")
                }
                try await Task.sleep(for: .milliseconds(10))
            }
        }
    }

    /// The error is the given location-name error. Compared by reflection, like `expect` (two copies of the core).
    private static func expectName(_ expected: LocationNameError, from work: () async throws -> Void) async throws {
        do {
            try await work()
        } catch {
            if String(reflecting: error) == String(reflecting: expected) { return }
            throw ContractFailure(description: "expected \(expected) but got \(error)")
        }
        throw ContractFailure(description: "expected \(expected) but nothing was thrown")
    }

    private static func observe<Value: Sendable>(
        _ stream: AsyncThrowingStream<Value, any Error>
    ) -> (Observed<Value>, Task<Void, Never>) {
        let seen = Observed<Value>()
        let task = Task {
            do { for try await value in stream { await seen.record(value) } } catch {}
        }
        return (seen, task)
    }

    static var locationManagementChecks: [Check] {
        [
            Check(name: "deleted locations disappear from all and defaultLocation") { env in
                let home = env.location("Home", isDefault: true)
                let garage = env.location("Garage", sortOrder: 1)
                try await env.locations.insert(home)
                try await env.locations.insert(garage)
                try await env.locations.deleteLocation(id: garage.id, movingBoxesTo: nil)
                try require(try await env.locations.all().map(\.id) == [home.id], "deleted location listed")
                try require(try await env.locations.defaultLocation()?.id == home.id, "default changed")
            },
            Check(name: "create places a location last and rejects a normalized duplicate name") { env in
                try await env.locations.insert(env.location("Home", isDefault: true))
                let kitchen = try await env.locations.create(named: "  Cuisine ")
                try require(kitchen.name == "Cuisine" && kitchen.sortOrder == 1 && !kitchen.isDefault, "created wrongly")
                try await expect(.duplicateName) { _ = try await env.locations.create(named: "cuísine") }
                try await expectName(.empty) { _ = try await env.locations.create(named: "   ") }
                try await expectName(.tooLong) {
                    _ = try await env.locations.create(named: String(repeating: "a", count: 51))
                }
                let garage = try await env.locations.create(named: "Garage")
                try require(garage.sortOrder == 2, "second location must be placed after the first")
                try require(try await env.locations.all().count == 3, "rejected creations must not persist")
            },
            Check(name: "rename rejects a duplicate name and allows a case change of its own name") { env in
                try await env.locations.insert(env.location("Home", isDefault: true))
                let kitchen = try await env.locations.create(named: "Cuisine")
                let garage = try await env.locations.create(named: "Garage")
                env.clock.advance(by: 30)
                try await env.locations.rename(id: kitchen.id, to: "CUISINE")
                let renamed = try await env.locations.all().first { $0.id == kitchen.id }
                try require(renamed?.name == "CUISINE" && renamed?.updatedAt == env.clock.now, "rename not stored")
                try require(renamed?.createdAt == kitchen.createdAt, "createdAt must be kept")
                try await expect(.duplicateName) { try await env.locations.rename(id: garage.id, to: "cuisine") }
                try await expect(.notFound) { try await env.locations.rename(id: UUID(), to: "Anything") }
            },
            Check(name: "reorder sets sortOrder and rejects an incomplete id list") { env in
                let first = env.location("A", isDefault: true, sortOrder: 0)
                let second = env.location("B", sortOrder: 1)
                let third = env.location("C", sortOrder: 2)
                for location in [first, second, third] { try await env.locations.insert(location) }
                try await env.locations.reorder([third.id, first.id, second.id])
                let reordered = try await env.locations.all()
                try require(reordered.map(\.id) == [third.id, first.id, second.id], "order not applied")
                try require(reordered.map(\.sortOrder) == [0, 1, 2], "sortOrder must be the index")
                env.clock.advance(by: 30)
                try await env.locations.reorder([third.id, second.id, first.id])
                let swapped = try await env.locations.all()
                try require(swapped.first?.updatedAt != env.clock.now, "an unmoved location must not be stamped")
                try require(swapped.dropFirst().allSatisfy { $0.updatedAt == env.clock.now }, "moved ones are stamped")
                try await expect(.notFound) { try await env.locations.reorder([first.id, second.id]) }
                try await expect(.notFound) { try await env.locations.reorder([first.id, second.id, first.id]) }
                try require(try await env.locations.all().map(\.id) == [third.id, second.id, first.id], "order changed")
            },
            Check(name: "setDefault leaves exactly one default") { env in
                let home = env.location("Home", isDefault: true)
                let garage = env.location("Garage", sortOrder: 1)
                try await env.locations.insert(home)
                try await env.locations.insert(garage)
                try await env.locations.setDefault(id: garage.id)
                try require(try await env.locations.defaultLocation()?.id == garage.id, "default not moved")
                try require(try await env.locations.all().filter(\.isDefault).count == 1, "exactly one default")
                try await env.locations.setDefault(id: garage.id)
                try require(try await env.locations.all().filter(\.isDefault).count == 1, "idempotent")
                try await expect(.notFound) { try await env.locations.setDefault(id: UUID()) }
            },
            Check(name: "the default location cannot be deleted") { env in
                let home = env.location("Home", isDefault: true)
                try await env.locations.insert(home)
                try await expect(.defaultLocation) { try await env.locations.deleteLocation(id: home.id, movingBoxesTo: nil) }
                try require(try await env.locations.all().map(\.id) == [home.id], "default must stay")
            },
            Check(name: "deleting a location with cabinet boxes needs a destination") { env in
                let home = env.location("Home", isDefault: true)
                let garage = env.location("Garage", sortOrder: 1)
                try await env.locations.insert(home)
                try await env.locations.insert(garage)
                let inside = env.box(in: garage)
                let gone = env.box(in: garage)
                try await env.boxes.insert(inside)
                try await env.boxes.insert(gone)
                try await env.boxes.markRemoved(id: gone.id, reason: .usedUp, at: env.clock.now)
                try require(try await env.locations.cabinetBoxCount(in: garage.id) == 1, "removed boxes must not count")
                try require(try await env.locations.cabinetBoxCount(in: UUID()) == 0, "unknown location counts zero")
                try await expect(.locationNotEmpty) { try await env.locations.deleteLocation(id: garage.id, movingBoxesTo: nil) }
                try await expect(.unknownLocation) { try await env.locations.deleteLocation(id: garage.id, movingBoxesTo: garage.id) }
                try await expect(.unknownLocation) { try await env.locations.deleteLocation(id: garage.id, movingBoxesTo: UUID()) }
                try await expect(.notFound) { try await env.locations.deleteLocation(id: UUID(), movingBoxesTo: nil) }
                try require(try await env.locations.all().count == 2, "failed deletions must change nothing")
                try require(try await env.boxes.box(id: inside.id)?.locationID == garage.id, "box must not move")
            },
            Check(name: "delete moving boxes moves only boxes in the cabinet and tombstones the location") { env in
                let home = env.location("Home", isDefault: true)
                let garage = env.location("Garage", sortOrder: 1)
                try await env.locations.insert(home)
                try await env.locations.insert(garage)
                let inside = env.box(in: garage)
                let removed = env.box(in: garage)
                let mistaken = env.box(in: garage)
                for box in [inside, removed, mistaken] { try await env.boxes.insert(box) }
                try await env.boxes.markRemoved(id: removed.id, reason: .thrownAway, at: env.clock.now)
                try await env.boxes.delete(id: mistaken.id)
                env.clock.advance(by: 30)
                try await env.locations.deleteLocation(id: garage.id, movingBoxesTo: home.id)
                try require(try await env.locations.all().map(\.id) == [home.id], "location must be deleted")
                let moved = try await env.boxes.box(id: inside.id)
                try require(moved?.locationID == home.id && moved?.updatedAt == env.clock.now, "box must move and be stamped")
                try require(try await env.boxes.box(id: removed.id)?.locationID == garage.id, "removed box keeps its location")
                try await env.boxes.undoDelete(id: mistaken.id)
                try require(try await env.boxes.box(id: mistaken.id)?.locationID == garage.id, "deleted box keeps its location")
            },
            Check(name: "undoDelete restores an empty deleted location unless its name was taken") { env in
                let home = env.location("Home", isDefault: true)
                try await env.locations.insert(home)
                let car = try await env.locations.create(named: "Voiture")
                try await env.locations.deleteLocation(id: car.id, movingBoxesTo: nil)
                try await env.locations.undoDeleteLocation(id: car.id)
                let restored = try await env.locations.all()
                try require(restored.map(\.id) == [home.id, car.id] && restored.last?.sortOrder == car.sortOrder, "not restored in place")
                try await env.locations.deleteLocation(id: car.id, movingBoxesTo: nil)
                _ = try await env.locations.create(named: "voiture")
                try await expect(.duplicateName) { try await env.locations.undoDeleteLocation(id: car.id) }
                try await expect(.notFound) { try await env.locations.undoDeleteLocation(id: home.id) }
                try await expect(.notFound) { try await env.locations.undoDeleteLocation(id: UUID()) }
            },
            Check(name: "boxes cannot be inserted into or moved to a deleted location") { env in
                let home = env.location("Home", isDefault: true)
                let garage = env.location("Garage", sortOrder: 1)
                try await env.locations.insert(home)
                try await env.locations.insert(garage)
                try await env.locations.deleteLocation(id: garage.id, movingBoxesTo: nil)
                try await expect(.unknownLocation) { try await env.boxes.insert(env.box(in: garage)) }
                var box = env.box(in: home)
                try await env.boxes.insert(box)
                box.locationID = garage.id
                try await expect(.unknownLocation) { try await env.boxes.update(box) }
            },
            Check(name: "observeCabinet emits after each write and excludes removed and deleted boxes") { env in
                let home = env.location("Home", isDefault: true)
                try await env.locations.insert(home)
                let (seen, task) = observe(env.boxes.observeCabinet())
                defer { task.cancel() }
                try await seen.wait("the initial, empty cabinet") { $0.isEmpty }
                let box = env.box(in: home)
                try await env.boxes.insert(box)
                try await seen.wait("the inserted box") { $0.map(\.id) == [box.id] }
                try await env.boxes.markRemoved(id: box.id, reason: .usedUp, at: env.clock.now)
                try await seen.wait("the removed box to disappear") { $0.isEmpty }
                try await env.boxes.undoRemoval(id: box.id)
                try await seen.wait("the put-back box") { $0.map(\.id) == [box.id] }
                try await env.boxes.delete(id: box.id)
                try await seen.wait("the deleted box to disappear") { $0.isEmpty }
            },
            Check(name: "observeAll emits after location changes") { env in
                let home = env.location("Home", isDefault: true)
                try await env.locations.insert(home)
                let (seen, task) = observe(env.locations.observeAll())
                defer { task.cancel() }
                try await seen.wait("the initial list") { $0.map(\.id) == [home.id] }
                let kitchen = try await env.locations.create(named: "Cuisine")
                try await seen.wait("the created location") { $0.map(\.id) == [home.id, kitchen.id] }
                try await env.locations.rename(id: kitchen.id, to: "Cave")
                try await seen.wait("the new name") { $0.last?.name == "Cave" }
                try await env.locations.deleteLocation(id: kitchen.id, movingBoxesTo: nil)
                try await seen.wait("the deleted location to disappear") { $0.map(\.id) == [home.id] }
            }
        ]
    }
}
