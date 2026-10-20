import Foundation
import MediCabinetCore

/// In-memory fake implementing the location and box ports; also documents the expected behaviour. The named-product
/// port is `named` (`InMemoryCabinetStore+NamedProducts.swift`): it shares this state, but is a separate value
/// because `LocationRepository.rename(id:to:)` and `NamedProductRepository.rename(id:to:)` have the same signature.
public actor InMemoryCabinetStore: LocationRepository, BoxRepository {
    private var locations: [Location.ID: Location] = [:]
    private var deletedLocations: Set<Location.ID> = []
    var boxes: [Box.ID: Box] = [:]
    var deleted: Set<Box.ID> = []
    let clock: any Clock
    private var locationObservers: [UUID: AsyncThrowingStream<[Location], any Error>.Continuation] = [:]
    private var boxObservers: [UUID: AsyncThrowingStream<[Box], any Error>.Continuation] = [:]
    var namedProducts: [NamedProduct.ID: NamedProduct] = [:]
    var forgottenProducts: Set<NamedProduct.ID> = []
    var backfillIsPending = true
    var summaryObservers: [UUID: AsyncThrowingStream<[NamedProductSummary], any Error>.Continuation] = [:]

    public init(clock: any Clock) {
        self.clock = clock
    }

    // MARK: LocationRepository

    public func all() async throws -> [Location] {
        liveLocations()
    }

    public func defaultLocation() async throws -> Location? {
        liveLocations().first { $0.isDefault }
    }

    public func insert(_ location: Location) async throws {
        guard locations[location.id] == nil else { throw RepositoryError.duplicate }
        if location.isDefault, liveLocations().contains(where: \.isDefault) {
            throw RepositoryError.duplicateDefault
        }
        locations[location.id] = location
        notify()
    }

    public func ensureDefault(named name: String) async throws -> Location {
        if let existing = liveLocations().first(where: \.isDefault) { return existing }
        let location = Location(name: name, isDefault: true, createdAt: clock.now)
        locations[location.id] = location
        notify()
        return location
    }

    public func create(named name: String) async throws -> Location {
        let valid = try validName(name, excluding: nil)
        let last = liveLocations().map(\.sortOrder).max() ?? -1
        let location = Location(name: valid, sortOrder: last + 1, createdAt: clock.now)
        locations[location.id] = location
        notify()
        return location
    }

    public func rename(id: Location.ID, to name: String) async throws {
        guard var stored = liveLocation(id) else { throw RepositoryError.notFound }
        stored.name = try validName(name, excluding: id)
        stored.updatedAt = clock.now
        locations[id] = stored
        notify()
    }

    public func reorder(_ ids: [Location.ID]) async throws {
        let live = liveLocations()
        guard ids.count == live.count, Set(ids) == Set(live.map(\.id)) else { throw RepositoryError.notFound }
        for (index, id) in ids.enumerated() {
            guard var stored = locations[id], stored.sortOrder != index else { continue }
            stored.sortOrder = index
            stored.updatedAt = clock.now
            locations[id] = stored
        }
        notify()
    }

    public func setDefault(id: Location.ID) async throws {
        guard liveLocation(id) != nil else { throw RepositoryError.notFound }
        for var stored in liveLocations() where stored.isDefault != (stored.id == id) {
            stored.isDefault = stored.id == id
            stored.updatedAt = clock.now
            locations[stored.id] = stored
        }
        notify()
    }

    public func cabinetBoxCount(in id: Location.ID) async throws -> Int {
        currentActiveBoxes().filter { $0.locationID == id }.count
    }

    public func deleteLocation(id: Location.ID, movingBoxesTo destination: Location.ID?) async throws {
        guard var stored = liveLocation(id) else { throw RepositoryError.notFound }
        guard !stored.isDefault else { throw RepositoryError.defaultLocation }
        if let destination, destination == id || liveLocation(destination) == nil {
            throw RepositoryError.unknownLocation
        }
        let inside = currentActiveBoxes().filter { $0.locationID == id }
        if !inside.isEmpty, destination == nil { throw RepositoryError.locationNotEmpty }
        if let destination {
            for var box in inside {
                box.locationID = destination
                box.updatedAt = clock.now
                boxes[box.id] = box
            }
        }
        stored.updatedAt = clock.now
        locations[id] = stored
        deletedLocations.insert(id)
        notify()
    }

    public func undoDeleteLocation(id: Location.ID) async throws {
        guard var stored = locations[id], deletedLocations.contains(id) else { throw RepositoryError.notFound }
        _ = try validName(stored.name, excluding: id)
        stored.updatedAt = clock.now
        locations[id] = stored
        deletedLocations.remove(id)
        notify()
    }

    public nonisolated func observeAll() -> AsyncThrowingStream<[Location], any Error> {
        AsyncThrowingStream { continuation in
            let token = UUID()
            Task { await self.addLocationObserver(token, continuation) }
            continuation.onTermination = { _ in Task { await self.removeObserver(token) } }
        }
    }

    // MARK: BoxRepository

    public func activeBoxes() async throws -> [Box] {
        currentActiveBoxes()
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

    public func latestBoxPerGTIN() async throws -> [Box] {
        var newest: [String: Box] = [:]
        for box in boxes.values where !deleted.contains(box.id) {
            guard let gtin = box.product.gtin else { continue }
            if let current = newest[gtin], (current.createdAt, current.id.uuidString) >= (box.createdAt, box.id.uuidString) {
                continue
            }
            newest[gtin] = box
        }
        return newest.values.sorted { ($0.createdAt, $0.id.uuidString) > ($1.createdAt, $1.id.uuidString) }
    }

    public func cabinetBoxes(gtin: String) async throws -> [Box] {
        boxes.values.filter { $0.isInCabinet && !deleted.contains($0.id) && $0.product.gtin == gtin }
            .sorted { ($0.createdAt, $0.id.uuidString) < ($1.createdAt, $1.id.uuidString) }
    }

    public func insert(_ box: Box) async throws {
        guard boxes[box.id] == nil else { throw RepositoryError.duplicate }
        guard liveLocation(box.locationID) != nil else { throw RepositoryError.unknownLocation }
        guard !serialTaken(by: box) else { throw RepositoryError.duplicateSerial }
        boxes[box.id] = box
        notify()
    }

    public func update(_ box: Box) async throws {
        guard let stored = boxes[box.id], !deleted.contains(box.id) else { throw RepositoryError.notFound }
        guard liveLocation(box.locationID) != nil else { throw RepositoryError.unknownLocation }
        guard !serialTaken(by: box) else { throw RepositoryError.duplicateSerial }
        boxes[box.id] = Box(
            id: stored.id, product: box.product, category: box.category,
            categoryOverridden: box.categoryOverridden, locationID: box.locationID,
            expiry: box.expiry, lot: box.lot, serial: box.serial, isOpened: box.isOpened,
            createdAt: stored.createdAt, updatedAt: clock.now, removal: stored.removal
        )
        notify()
    }

    public func markRemoved(id: Box.ID, reason: RemovalReason, at date: Date) async throws {
        guard var stored = boxes[id], !deleted.contains(id) else { throw RepositoryError.notFound }
        stored.removal = Box.Removal(reason: reason, date: date)
        stored.updatedAt = clock.now
        boxes[id] = stored
        notify()
    }

    public func undoRemoval(id: Box.ID) async throws {
        guard var stored = boxes[id], !deleted.contains(id) else { throw RepositoryError.notFound }
        stored.removal = nil
        stored.updatedAt = clock.now
        boxes[id] = stored
        notify()
    }

    public func delete(id: Box.ID) async throws {
        guard var stored = boxes[id], !deleted.contains(id) else { throw RepositoryError.notFound }
        stored.updatedAt = clock.now
        boxes[id] = stored
        deleted.insert(id)
        notify()
    }

    public func undoDelete(id: Box.ID) async throws {
        guard var stored = boxes[id], deleted.contains(id) else { throw RepositoryError.notFound }
        guard !serialTaken(by: stored) else { throw RepositoryError.duplicateSerial }
        stored.updatedAt = clock.now
        boxes[id] = stored
        deleted.remove(id)
        notify()
    }

    public nonisolated func observeCabinet() -> AsyncThrowingStream<[Box], any Error> {
        AsyncThrowingStream { continuation in
            let token = UUID()
            Task { await self.addBoxObserver(token, continuation) }
            continuation.onTermination = { _ in Task { await self.removeObserver(token) } }
        }
    }

    // MARK: Private

    private func liveLocation(_ id: Location.ID) -> Location? {
        deletedLocations.contains(id) ? nil : locations[id]
    }

    /// Ordered by `sortOrder`, then name.
    private func liveLocations() -> [Location] {
        locations.values.filter { !deletedLocations.contains($0.id) }
            .sorted { ($0.sortOrder, $0.name) < ($1.sortOrder, $1.name) }
    }

    func currentActiveBoxes() -> [Box] {
        boxes.values.filter { $0.isInCabinet && !deleted.contains($0.id) }.sorted { $0.createdAt < $1.createdAt }
    }

    /// Trims and validates `raw` against the other locations that are not deleted.
    private func validName(_ raw: String, excluding id: Location.ID?) throws -> String {
        let others = liveLocations().filter { $0.id != id }.map(\.name)
        do {
            return try LocationName.validate(raw, existing: others)
        } catch LocationNameError.duplicateName {
            throw RepositoryError.duplicateName
        }
    }

    /// Another non-deleted box already has this box's GTIN and serial (boxes without both never conflict).
    private func serialTaken(by box: Box) -> Bool {
        guard let gtin = box.product.gtin, let serial = box.serial else { return false }
        return boxes.values.contains {
            $0.id != box.id && !deleted.contains($0.id) && $0.product.gtin == gtin && $0.serial == serial
        }
    }

    private func addLocationObserver(
        _ token: UUID, _ continuation: AsyncThrowingStream<[Location], any Error>.Continuation
    ) {
        locationObservers[token] = continuation
        continuation.yield(liveLocations())
    }

    private func addBoxObserver(_ token: UUID, _ continuation: AsyncThrowingStream<[Box], any Error>.Continuation) {
        boxObservers[token] = continuation
        continuation.yield(currentActiveBoxes())
    }

    func removeObserver(_ token: UUID) {
        locationObservers[token] = nil
        boxObservers[token] = nil
        summaryObservers[token] = nil
    }

    /// Tells the observers after every write.
    func notify() {
        let currentLocations = liveLocations()
        let currentBoxes = currentActiveBoxes()
        for continuation in locationObservers.values { continuation.yield(currentLocations) }
        for continuation in boxObservers.values { continuation.yield(currentBoxes) }
        if !summaryObservers.isEmpty {
            let summaries = currentSummaries()
            for continuation in summaryObservers.values { continuation.yield(summaries) }
        }
    }
}
