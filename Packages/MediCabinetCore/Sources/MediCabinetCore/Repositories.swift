import Foundation

public enum RepositoryError: Error, Equatable, Sendable {
    case notFound
    case unknownLocation
    case duplicate
    case duplicateDefault
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

public protocol BoxRepository: Sendable {
    /// Boxes still in the cabinet (`removal == nil`).
    func activeBoxes() async throws -> [Box]
    /// Returns removed boxes too.
    func box(id: Box.ID) async throws -> Box?
    /// Throws `.duplicate` for a known id and `.unknownLocation` for a missing location.
    func insert(_ box: Box) async throws
    /// Throws `.notFound` / `.unknownLocation`. Preserves `createdAt` and `removal`.
    func update(_ box: Box) async throws
    func markRemoved(id: Box.ID, reason: RemovalReason, at date: Date) async throws
    func undoRemoval(id: Box.ID) async throws
}
