import Foundation
import GRDB
import MediCabinetCore

struct GRDBLocationRepository: LocationRepository {
    let database: AppDatabase
    let clock: any Clock

    /// Locations that are not deleted.
    private static var live: QueryInterfaceRequest<LocationRecord> {
        LocationRecord.filter(Column("deletedAt") == nil)
    }

    private static func liveRecord(_ id: Location.ID, _ db: Database) throws -> LocationRecord? {
        try live.filter(Column("id") == id.uuidString).fetchOne(db)
    }

    private static func liveLocations(_ db: Database) throws -> [Location] {
        try live.order(Column("sortOrder"), Column("name")).fetchAll(db).map { try $0.model() }
    }

    func all() async throws -> [Location] {
        try await database.writer.read { db in try Self.liveLocations(db) }
    }

    func defaultLocation() async throws -> Location? {
        try await database.writer.read { db in
            try Self.live.filter(Column("isDefault") == true).fetchOne(db)?.model()
        }
    }

    func insert(_ location: Location) async throws {
        try await database.writer.write { db in
            if try LocationRecord.fetchOne(db, key: location.id.uuidString) != nil { throw RepositoryError.duplicate }
            if location.isDefault, try Self.live.filter(Column("isDefault") == true).fetchCount(db) > 0 {
                throw RepositoryError.duplicateDefault
            }
            try LocationRecord(location).insert(db)
        }
    }

    func ensureDefault(named name: String) async throws -> Location {
        let now = clock.now
        return try await database.writer.write { db in
            if let existing = try Self.live.filter(Column("isDefault") == true).fetchOne(db) {
                return try existing.model()
            }
            let location = Location(name: name, isDefault: true, createdAt: now)
            try LocationRecord(location).insert(db)
            return location
        }
    }

    func create(named name: String) async throws -> Location {
        let now = clock.now
        return try await database.writer.write { db in
            let valid = try Self.validName(name, among: try Self.live.fetchAll(db).map(\.name))
            let last = try Int.fetchOne(db, sql: "SELECT MAX(sortOrder) FROM location WHERE deletedAt IS NULL") ?? -1
            let location = Location(name: valid, sortOrder: last + 1, createdAt: now)
            try LocationRecord(location).insert(db)
            return location
        }
    }

    func rename(id: Location.ID, to name: String) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var record = try Self.liveRecord(id, db) else { throw RepositoryError.notFound }
            let others = try Self.live.filter(Column("id") != record.id).fetchAll(db).map(\.name)
            record.name = try Self.validName(name, among: others)
            record.updatedAt = now
            try record.update(db)
        }
    }

    func reorder(_ ids: [Location.ID]) async throws {
        let now = clock.now
        try await database.writer.write { db in
            let records = try Self.live.fetchAll(db)
            let wanted = ids.map(\.uuidString)
            guard wanted.count == records.count, Set(wanted) == Set(records.map(\.id)) else {
                throw RepositoryError.notFound
            }
            let byID = Dictionary(uniqueKeysWithValues: records.map { ($0.id, $0) })
            for (index, id) in wanted.enumerated() {
                guard var record = byID[id], record.sortOrder != index else { continue }
                record.sortOrder = index
                record.updatedAt = now
                try record.update(db)
            }
        }
    }

    func setDefault(id: Location.ID) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var target = try Self.liveRecord(id, db) else { throw RepositoryError.notFound }
            // Clear the old default first: the unique index allows a single default at any time.
            for var old in try Self.live.filter(Column("isDefault") == true && Column("id") != target.id).fetchAll(db) {
                old.isDefault = false
                old.updatedAt = now
                try old.update(db)
            }
            if !target.isDefault {
                target.isDefault = true
                target.updatedAt = now
                try target.update(db)
            }
        }
    }

    func cabinetBoxCount(in id: Location.ID) async throws -> Int {
        try await database.writer.read { db in
            try BoxRecord.filter(
                Column("locationId") == id.uuidString && Column("removedAt") == nil && Column("deletedAt") == nil
            ).fetchCount(db)
        }
    }

    func deleteLocation(id: Location.ID, movingBoxesTo destination: Location.ID?) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var record = try Self.liveRecord(id, db) else { throw RepositoryError.notFound }
            guard !record.isDefault else { throw RepositoryError.defaultLocation }
            if let destination, try destination == id || Self.liveRecord(destination, db) == nil {
                throw RepositoryError.unknownLocation
            }
            let inside = try BoxRecord.filter(
                Column("locationId") == record.id && Column("removedAt") == nil && Column("deletedAt") == nil
            ).fetchCount(db)
            if inside > 0, destination == nil { throw RepositoryError.locationNotEmpty }
            if let destination, inside > 0 {
                try db.execute(
                    sql: """
                        UPDATE box SET locationId = ?, updatedAt = ?
                        WHERE locationId = ? AND removedAt IS NULL AND deletedAt IS NULL
                        """,
                    arguments: [destination.uuidString, now, record.id]
                )
            }
            record.deletedAt = now
            record.updatedAt = now
            try record.update(db)
        }
    }

    func undoDeleteLocation(id: Location.ID) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var record = try LocationRecord.fetchOne(db, key: id.uuidString), record.deletedAt != nil else {
                throw RepositoryError.notFound
            }
            let others = try Self.live.fetchAll(db).map(\.name)
            _ = try Self.validName(record.name, among: others)
            record.deletedAt = nil
            record.updatedAt = now
            try record.update(db)
        }
    }

    func observeAll() -> AsyncThrowingStream<[Location], any Error> {
        let observation = ValueObservation.tracking { db in try Self.liveLocations(db) }
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for try await locations in observation.values(in: database.writer) { continuation.yield(locations) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Trims and validates `raw` against the other locations' names; a duplicate is a repository error.
    private static func validName(_ raw: String, among others: [String]) throws -> String {
        do {
            return try LocationName.validate(raw, existing: others)
        } catch LocationNameError.duplicateName {
            throw RepositoryError.duplicateName
        }
    }
}
