import Foundation
import GRDB
import MediCabinetCore

struct GRDBLocationRepository: LocationRepository {
    let database: AppDatabase
    let clock: any Clock

    func all() async throws -> [Location] {
        try await database.writer.read { db in
            try LocationRecord.order(Column("sortOrder"), Column("name")).fetchAll(db).map { try $0.model() }
        }
    }

    func defaultLocation() async throws -> Location? {
        try await database.writer.read { db in
            try LocationRecord.filter(Column("isDefault") == true).fetchOne(db)?.model()
        }
    }

    func insert(_ location: Location) async throws {
        try await database.writer.write { db in
            if try LocationRecord.fetchOne(db, key: location.id.uuidString) != nil { throw RepositoryError.duplicate }
            if location.isDefault, try LocationRecord.filter(Column("isDefault") == true).fetchCount(db) > 0 {
                throw RepositoryError.duplicateDefault
            }
            try LocationRecord(location).insert(db)
        }
    }

    func ensureDefault(named name: String) async throws -> Location {
        let now = clock.now
        return try await database.writer.write { db in
            if let existing = try LocationRecord.filter(Column("isDefault") == true).fetchOne(db) {
                return try existing.model()
            }
            let location = Location(name: name, isDefault: true, createdAt: now)
            try LocationRecord(location).insert(db)
            return location
        }
    }
}
