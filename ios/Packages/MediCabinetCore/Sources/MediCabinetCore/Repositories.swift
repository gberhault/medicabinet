import Foundation

public enum RepositoryError: Error, Equatable, Sendable {
    case notFound
    case unknownLocation
    case duplicate
    case duplicateDefault
    /// Another box that is not deleted already has this GTIN and serial.
    case duplicateSerial
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
    /// Throws `.duplicate` for a known id (deleted or not), `.unknownLocation` for a missing location and
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
