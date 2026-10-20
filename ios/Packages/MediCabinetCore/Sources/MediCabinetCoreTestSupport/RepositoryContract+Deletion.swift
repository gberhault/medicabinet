import Foundation
import MediCabinetCore

/// Checks for deleted boxes (tombstones), the unique serial and the lookups the scan uses.
extension RepositoryContract {
    static var deletionChecks: [Check] {
        [
            Check(name: "a deleted box disappears from every query") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let box = env.box(in: location, serial: "S1")
                try await env.boxes.insert(box)
                try await env.boxes.delete(id: box.id)
                try require(try await env.boxes.activeBoxes().isEmpty, "a deleted box must not be active")
                try require(try await env.boxes.box(id: box.id) == nil, "a deleted box must not be found by id")
                try require(try await env.boxes.box(gtin: "03400930000001", serial: "S1") == nil, "found by serial")
                try require(try await env.boxes.latestBox(gtin: "03400930000001") == nil, "found as latest")
            },
            Check(name: "undoDelete restores a deleted box") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let box = env.box(in: location)
                try await env.boxes.insert(box)
                try await env.boxes.delete(id: box.id)
                env.clock.advance(by: 30)
                try await env.boxes.undoDelete(id: box.id)
                try require(try await env.boxes.activeBoxes().map(\.id) == [box.id], "box should be active again")
                try require(try await env.boxes.box(id: box.id)?.updatedAt == env.clock.now, "updatedAt must be bumped")
                try require(try await env.boxes.box(id: box.id)?.createdAt == box.createdAt, "createdAt must be kept")
            },
            Check(name: "delete and undoDelete on unknown or wrong-state boxes throw notFound") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let ghost = env.box(in: location)
                try await expect(.notFound) { try await env.boxes.delete(id: ghost.id) }
                try await expect(.notFound) { try await env.boxes.undoDelete(id: ghost.id) }
                let box = env.box(in: location)
                try await env.boxes.insert(box)
                try await expect(.notFound) { try await env.boxes.undoDelete(id: box.id) }
                try await env.boxes.delete(id: box.id)
                try await expect(.notFound) { try await env.boxes.delete(id: box.id) }
                try await expect(.notFound) { try await env.boxes.update(box) }
                try await expect(.notFound) { try await env.boxes.markRemoved(id: box.id, reason: .usedUp, at: env.clock.now) }
                try await expect(.notFound) { try await env.boxes.undoRemoval(id: box.id) }
            },
            Check(name: "a second box with the same gtin and serial is rejected with duplicateSerial") { env in
                let location = env.location()
                try await env.locations.insert(location)
                try await env.boxes.insert(env.box(in: location, serial: "S1"))
                try await expect(.duplicateSerial) { try await env.boxes.insert(env.box(in: location, serial: "S1")) }
                try await env.boxes.insert(env.box(in: location, gtin: "03400930000002", serial: "S1"))
                try await env.boxes.insert(env.box(in: location, serial: "S2"))
                try await env.boxes.insert(env.box(in: location, serial: nil))
                try await env.boxes.insert(env.box(in: location, serial: nil))
                try require(try await env.boxes.activeBoxes().count == 5, "only the duplicate must be rejected")
            },
            Check(name: "a deleted box frees its serial") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let first = env.box(in: location, serial: "S1")
                try await env.boxes.insert(first)
                try await env.boxes.delete(id: first.id)
                let second = env.box(in: location, serial: "S1")
                try await env.boxes.insert(second)
                try require(try await env.boxes.box(gtin: "03400930000001", serial: "S1")?.id == second.id, "wrong box")
            },
            Check(name: "box by gtin and serial returns removed boxes but not deleted ones") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let removed = env.box(in: location, serial: "S1")
                let deleted = env.box(in: location, serial: "S2")
                try await env.boxes.insert(removed)
                try await env.boxes.insert(deleted)
                try await env.boxes.markRemoved(id: removed.id, reason: .usedUp, at: env.clock.now)
                try await env.boxes.delete(id: deleted.id)
                let found = try await env.boxes.box(gtin: "03400930000001", serial: "S1")
                try require(found?.id == removed.id && found?.removal?.reason == .usedUp, "removed box must be found")
                try require(try await env.boxes.box(gtin: "03400930000001", serial: "S2") == nil, "deleted box found")
                try require(try await env.boxes.box(gtin: "03400930000001", serial: "S3") == nil, "unknown serial found")
            },
            Check(name: "latestBox returns the most recent non-deleted box of a gtin") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let older = env.box(in: location, name: "Older")
                try await env.boxes.insert(older)
                env.clock.advance(by: 10)
                let newer = env.box(in: location, name: "Newer")
                try await env.boxes.insert(newer)
                env.clock.advance(by: 10)
                try await env.boxes.insert(env.box(in: location, name: "Other", gtin: "03400930000002"))
                try require(try await env.boxes.latestBox(gtin: "03400930000001")?.id == newer.id, "newest expected")
                try await env.boxes.delete(id: newer.id)
                try require(try await env.boxes.latestBox(gtin: "03400930000001")?.id == older.id, "older expected")
                try require(try await env.boxes.latestBox(gtin: "03400930000009") == nil, "unknown gtin must be nil")
            },
            Check(name: "update to a serial already in the cabinet is rejected") { env in
                let location = env.location()
                try await env.locations.insert(location)
                try await env.boxes.insert(env.box(in: location, serial: "S1"))
                var second = env.box(in: location, serial: "S2")
                try await env.boxes.insert(second)
                second.serial = "S1"
                try await expect(.duplicateSerial) { try await env.boxes.update(second) }
                try require(try await env.boxes.box(id: second.id)?.serial == "S2", "a rejected update must not persist")
            },
            Check(name: "undoDelete is rejected when the serial was taken meanwhile") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let first = env.box(in: location, serial: "S1")
                try await env.boxes.insert(first)
                try await env.boxes.delete(id: first.id)
                let second = env.box(in: location, serial: "S1")
                try await env.boxes.insert(second)
                try await expect(.duplicateSerial) { try await env.boxes.undoDelete(id: first.id) }
                try require(try await env.boxes.activeBoxes().map(\.id) == [second.id], "first must stay deleted")
            }
        ]
    }
}
