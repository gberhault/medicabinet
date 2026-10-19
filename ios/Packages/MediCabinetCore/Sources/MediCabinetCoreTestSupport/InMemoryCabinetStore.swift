import Foundation
import MediCabinetCore

/// In-memory fake implementing both ports; also documents the expected behaviour.
public actor InMemoryCabinetStore: LocationRepository, BoxRepository {
    private var locations: [Location.ID: Location] = [:]
    private var boxes: [Box.ID: Box] = [:]
    private let clock: any Clock

    public init(clock: any Clock) {
        self.clock = clock
    }

    // MARK: LocationRepository

    public func all() async throws -> [Location] {
        locations.values.sorted { ($0.sortOrder, $0.name) < ($1.sortOrder, $1.name) }
    }

    public func defaultLocation() async throws -> Location? {
        locations.values.first { $0.isDefault }
    }

    public func insert(_ location: Location) async throws {
        guard locations[location.id] == nil else { throw RepositoryError.duplicate }
        if location.isDefault, locations.values.contains(where: \.isDefault) {
            throw RepositoryError.duplicateDefault
        }
        locations[location.id] = location
    }

    public func ensureDefault(named name: String) async throws -> Location {
        if let existing = locations.values.first(where: \.isDefault) { return existing }
        let location = Location(name: name, isDefault: true, createdAt: clock.now)
        locations[location.id] = location
        return location
    }

    // MARK: BoxRepository

    public func activeBoxes() async throws -> [Box] {
        boxes.values.filter(\.isInCabinet).sorted { $0.createdAt < $1.createdAt }
    }

    public func box(id: Box.ID) async throws -> Box? {
        boxes[id]
    }

    public func insert(_ box: Box) async throws {
        guard boxes[box.id] == nil else { throw RepositoryError.duplicate }
        guard locations[box.locationID] != nil else { throw RepositoryError.unknownLocation }
        boxes[box.id] = box
    }

    public func update(_ box: Box) async throws {
        guard let stored = boxes[box.id] else { throw RepositoryError.notFound }
        guard locations[box.locationID] != nil else { throw RepositoryError.unknownLocation }
        var updated = box
        updated.updatedAt = clock.now
        updated.removal = stored.removal
        boxes[box.id] = Box(
            id: stored.id, product: updated.product, category: updated.category,
            categoryOverridden: updated.categoryOverridden, locationID: updated.locationID,
            expiry: updated.expiry, lot: updated.lot, serial: updated.serial, isOpened: updated.isOpened,
            createdAt: stored.createdAt, updatedAt: updated.updatedAt, removal: stored.removal
        )
    }

    public func markRemoved(id: Box.ID, reason: RemovalReason, at date: Date) async throws {
        guard var stored = boxes[id] else { throw RepositoryError.notFound }
        stored.removal = Box.Removal(reason: reason, date: date)
        stored.updatedAt = clock.now
        boxes[id] = stored
    }

    public func undoRemoval(id: Box.ID) async throws {
        guard var stored = boxes[id] else { throw RepositoryError.notFound }
        stored.removal = nil
        stored.updatedAt = clock.now
        boxes[id] = stored
    }
}
