import Foundation
import MediCabinetCore

/// Checks for the queries the Cabinet tab and removal by scan use.
extension RepositoryContract {
    static var cabinetChecks: [Check] {
        [
            Check(name: "cabinetBoxes returns only boxes in the cabinet with that gtin, oldest first") { env in
                let location = env.location()
                try await env.locations.insert(location)
                let first = env.box(in: location, name: "First")
                try await env.boxes.insert(first)
                env.clock.advance(by: 10)
                let removed = env.box(in: location, name: "Removed")
                try await env.boxes.insert(removed)
                env.clock.advance(by: 10)
                let deleted = env.box(in: location, name: "Deleted")
                try await env.boxes.insert(deleted)
                env.clock.advance(by: 10)
                let other = env.box(in: location, name: "Other", gtin: "03400930000002")
                try await env.boxes.insert(other)
                env.clock.advance(by: 10)
                let last = env.box(in: location, name: "Last")
                try await env.boxes.insert(last)
                try await env.boxes.markRemoved(id: removed.id, reason: .thrownAway, at: env.clock.now)
                try await env.boxes.delete(id: deleted.id)

                let found = try await env.boxes.cabinetBoxes(gtin: "03400930000001")
                try require(found.map(\.id) == [first.id, last.id], "expected the two boxes in the cabinet, oldest first")
                try require(try await env.boxes.cabinetBoxes(gtin: "03400930000002").map(\.id) == [other.id], "other gtin")
                try require(try await env.boxes.cabinetBoxes(gtin: "03400930000009").isEmpty, "unknown gtin must be empty")
            }
        ]
    }
}
