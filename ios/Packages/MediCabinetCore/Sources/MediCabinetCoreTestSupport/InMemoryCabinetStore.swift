import Foundation
import MediCabinetCore

/// In-memory fake implementing both ports; also documents the expected behaviour.
public actor InMemoryCabinetStore: LocationRepository, BoxRepository {
    private var locations: [Location.ID: Location] = [:]
    private var boxes: [Box.ID: Box] = [:]
    private var deleted: Set<Box.ID> = []
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
        boxes.values.filter { $0.isInCabinet && !deleted.contains($0.id) }.sorted { $0.createdAt < $1.createdAt }
    }

    public func box(id: Box.ID) async throws -> Box? {
        deleted.contains(id) ? nil : boxes[id]
    }

    public func box(gtin: String, serial: String) async throws -> Box? {
        boxes.values.first { !deleted.contains($0.id) && $0.product.gtin == gtin && $0.serial == serial }
    }

    public func latestBox(gtin: String) async throws -> Box? {
        boxes.values.filter { !deleted.contains($0.id) && $0.product.gtin == gtin }
            .max { ($0.createdAt, $0.id.uuidString) < ($1.createdAt, $1.id.uuidString) }
    }

    public func insert(_ box: Box) async throws {
        guard boxes[box.id] == nil else { throw RepositoryError.duplicate }
        guard locations[box.locationID] != nil else { throw RepositoryError.unknownLocation }
        guard !serialTaken(by: box) else { throw RepositoryError.duplicateSerial }
        boxes[box.id] = box
    }

    public func update(_ box: Box) async throws {
        guard let stored = boxes[box.id], !deleted.contains(box.id) else { throw RepositoryError.notFound }
        guard locations[box.locationID] != nil else { throw RepositoryError.unknownLocation }
        guard !serialTaken(by: box) else { throw RepositoryError.duplicateSerial }
        boxes[box.id] = Box(
            id: stored.id, product: box.product, category: box.category,
            categoryOverridden: box.categoryOverridden, locationID: box.locationID,
            expiry: box.expiry, lot: box.lot, serial: box.serial, isOpened: box.isOpened,
            createdAt: stored.createdAt, updatedAt: clock.now, removal: stored.removal
        )
    }

    public func markRemoved(id: Box.ID, reason: RemovalReason, at date: Date) async throws {
        guard var stored = boxes[id], !deleted.contains(id) else { throw RepositoryError.notFound }
        stored.removal = Box.Removal(reason: reason, date: date)
        stored.updatedAt = clock.now
        boxes[id] = stored
    }

    public func undoRemoval(id: Box.ID) async throws {
        guard var stored = boxes[id], !deleted.contains(id) else { throw RepositoryError.notFound }
        stored.removal = nil
        stored.updatedAt = clock.now
        boxes[id] = stored
    }

    public func delete(id: Box.ID) async throws {
        guard var stored = boxes[id], !deleted.contains(id) else { throw RepositoryError.notFound }
        stored.updatedAt = clock.now
        boxes[id] = stored
        deleted.insert(id)
    }

    public func undoDelete(id: Box.ID) async throws {
        guard var stored = boxes[id], deleted.contains(id) else { throw RepositoryError.notFound }
        guard !serialTaken(by: stored) else { throw RepositoryError.duplicateSerial }
        stored.updatedAt = clock.now
        boxes[id] = stored
        deleted.remove(id)
    }

    /// Another non-deleted box already has this box's GTIN and serial (boxes without both never conflict).
    private func serialTaken(by box: Box) -> Bool {
        guard let gtin = box.product.gtin, let serial = box.serial else { return false }
        return boxes.values.contains {
            $0.id != box.id && !deleted.contains($0.id) && $0.product.gtin == gtin && $0.serial == serial
        }
    }
}
