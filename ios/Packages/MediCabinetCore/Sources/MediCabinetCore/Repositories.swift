import Foundation

public enum RepositoryError: Error, Equatable, Sendable {
    case notFound
    case unknownLocation
    case duplicate
    case duplicateDefault
    /// Another box that is not deleted already has this GTIN and serial.
    case duplicateSerial
    /// Another location that is not deleted has the same name once accents, case and spacing are ignored.
    case duplicateName
    /// The default location cannot be deleted.
    case defaultLocation
    /// The location still holds boxes in the cabinet and no destination was given.
    case locationNotEmpty
}

/// Repositories stamp `updatedAt` from their injected `Clock` on every write after insert;
/// callers never set it.
public protocol LocationRepository: Sendable {
    /// Ordered by `sortOrder`, then name.
    func all() async throws -> [Location]
    func defaultLocation() async throws -> Location?
    /// Throws `.duplicate` for a known id and `.duplicateDefault` when a default already exists.
    func insert(_ location: Location) async throws
    /// Returns the default location, creating it with `name` when none exists. Idempotent.
    func ensureDefault(named name: String) async throws -> Location

    // Locations that are *deleted* (tombstoned by `deleteLocation`) are invisible to every operation of this port and of
    // `BoxRepository`, except `undoDeleteLocation`.

    /// Adds a location after the others. The name is validated by `LocationName`: throws `LocationNameError`
    /// (`empty`, `tooLong`) or `.duplicateName`.
    func create(named name: String) async throws -> Location
    /// Throws `.notFound` (unknown or deleted), `.duplicateName`, `LocationNameError`.
    func rename(id: Location.ID, to name: String) async throws
    /// `ids` must be exactly the locations that are not deleted, in the wanted order (else `.notFound`);
    /// `sortOrder` becomes the index. Only locations whose position changed are stamped.
    func reorder(_ ids: [Location.ID]) async throws
    /// Throws `.notFound`. Afterwards exactly one location is the default.
    func setDefault(id: Location.ID) async throws
    /// Boxes in the cabinet (not removed, not deleted) in this location; 0 for an unknown location.
    func cabinetBoxCount(in id: Location.ID) async throws -> Int
    /// Tombstones the location and, in the same transaction, moves its boxes in the cabinet to `destination`.
    /// Removed and deleted boxes keep pointing at it. Throws `.notFound` (unknown or already deleted),
    /// `.defaultLocation`, `.unknownLocation` (destination missing, deleted or the location itself),
    /// `.locationNotEmpty` (boxes in the cabinet and no destination).
    func deleteLocation(id: Location.ID, movingBoxesTo destination: Location.ID?) async throws
    /// Restores a deleted location in its former place. Throws `.notFound` when it is not deleted and
    /// `.duplicateName` when its name was taken meanwhile.
    func undoDeleteLocation(id: Location.ID) async throws
    /// The locations (as `all()`) now, then after every change.
    func observeAll() -> AsyncThrowingStream<[Location], any Error>
}

/// A *deleted* box is one added by mistake (never used up or thrown away): kept as a tombstone for the future
/// sync and export, and invisible to every operation below except `undoDelete`. A *removed* box is a stock
/// movement and stays visible to `box(id:)`.
public protocol BoxRepository: Sendable {
    /// Boxes still in the cabinet (`removal == nil`, not deleted).
    func activeBoxes() async throws -> [Box]
    /// Returns removed boxes too, never deleted ones.
    func box(id: Box.ID) async throws -> Box?
    /// Non-deleted box (in the cabinet or removed) with this GTIN and serial.
    func box(gtin: String, serial: String) async throws -> Box?
    /// Most recently created non-deleted box with this GTIN.
    func latestBox(gtin: String) async throws -> Box?
    /// Boxes in the cabinet (not removed, not deleted) with this GTIN, oldest first (`createdAt`, then id).
    func cabinetBoxes(gtin: String) async throws -> [Box]
    /// The boxes in the cabinet (as `activeBoxes()`) now, then after every change.
    func observeCabinet() -> AsyncThrowingStream<[Box], any Error>
    /// Throws `.duplicate` for a known id (deleted or not), `.unknownLocation` for a missing or deleted location and
    /// `.duplicateSerial` when another non-deleted box has the same GTIN and serial.
    func insert(_ box: Box) async throws
    /// Throws `.notFound` (unknown or deleted) / `.unknownLocation` / `.duplicateSerial`. Preserves
    /// `createdAt` and `removal`.
    func update(_ box: Box) async throws
    /// Throws `.notFound` for an unknown or deleted box.
    func markRemoved(id: Box.ID, reason: RemovalReason, at date: Date) async throws
    /// Throws `.notFound` for an unknown or deleted box.
    func undoRemoval(id: Box.ID) async throws
    /// Stamps the tombstone. Throws `.notFound` for an unknown or already deleted box.
    func delete(id: Box.ID) async throws
    /// Throws `.notFound` when the box is unknown or not deleted, `.duplicateSerial` when its serial was taken meanwhile.
    func undoDelete(id: Box.ID) async throws
}
