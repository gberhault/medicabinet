import Foundation
import GRDB
import MediCabinetCore

struct GRDBBoxRepository: BoxRepository {
    let database: AppDatabase
    let clock: any Clock

    func activeBoxes() async throws -> [Box] {
        try await database.writer.read { db in
            try BoxRecord.filter(Column("removedAt") == nil).order(Column("createdAt"), Column("id")).fetchAll(db).map { try $0.model() }
        }
    }

    func box(id: Box.ID) async throws -> Box? {
        try await database.writer.read { db in
            try BoxRecord.fetchOne(db, key: id.uuidString)?.model()
        }
    }

    func insert(_ box: Box) async throws {
        try await database.writer.write { db in
            if try BoxRecord.fetchOne(db, key: box.id.uuidString) != nil { throw RepositoryError.duplicate }
            guard try LocationRecord.fetchOne(db, key: box.locationID.uuidString) != nil else {
                throw RepositoryError.unknownLocation
            }
            try BoxRecord(box).insert(db)
        }
    }

    func update(_ box: Box) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard let stored = try BoxRecord.fetchOne(db, key: box.id.uuidString) else { throw RepositoryError.notFound }
            guard try LocationRecord.fetchOne(db, key: box.locationID.uuidString) != nil else {
                throw RepositoryError.unknownLocation
            }
            var record = BoxRecord(box)
            record.createdAt = stored.createdAt
            record.removalReason = stored.removalReason
            record.removedAt = stored.removedAt
            record.updatedAt = now
            try record.update(db)
        }
    }

    func markRemoved(id: Box.ID, reason: RemovalReason, at date: Date) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var record = try BoxRecord.fetchOne(db, key: id.uuidString) else { throw RepositoryError.notFound }
            record.removalReason = reason.rawValue
            record.removedAt = date
            record.updatedAt = now
            try record.update(db)
        }
    }

    func undoRemoval(id: Box.ID) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var record = try BoxRecord.fetchOne(db, key: id.uuidString) else { throw RepositoryError.notFound }
            record.removalReason = nil
            record.removedAt = nil
            record.updatedAt = now
            try record.update(db)
        }
    }
}
