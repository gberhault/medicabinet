import Foundation
import GRDB
import MediCabinetCore

struct GRDBBoxRepository: BoxRepository {
    let database: AppDatabase
    let clock: any Clock

    func activeBoxes() async throws -> [Box] {
        try await database.writer.read { db in
            try BoxRecord.filter(Column("removedAt") == nil && Column("deletedAt") == nil)
                .order(Column("createdAt"), Column("id")).fetchAll(db).map { try $0.model() }
        }
    }

    func box(id: Box.ID) async throws -> Box? {
        try await database.writer.read { db in
            try Self.liveRecord(db, id)?.model()
        }
    }

    func box(gtin: String, serial: String) async throws -> Box? {
        try await database.writer.read { db in
            try BoxRecord.filter(Column("gtin") == gtin && Column("serial") == serial && Column("deletedAt") == nil)
                .fetchOne(db)?.model()
        }
    }

    func latestBox(gtin: String) async throws -> Box? {
        try await database.writer.read { db in
            try BoxRecord.filter(Column("gtin") == gtin && Column("deletedAt") == nil)
                .order(Column("createdAt").desc, Column("id").desc).fetchOne(db)?.model()
        }
    }

    func cabinetBoxes(gtin: String) async throws -> [Box] {
        try await database.writer.read { db in
            try BoxRecord.filter(Column("gtin") == gtin && Column("removedAt") == nil && Column("deletedAt") == nil)
                .order(Column("createdAt"), Column("id")).fetchAll(db).map { try $0.model() }
        }
    }

    func insert(_ box: Box) async throws {
        try await database.writer.write { db in
            if try BoxRecord.fetchOne(db, key: box.id.uuidString) != nil { throw RepositoryError.duplicate }
            guard try LocationRecord.fetchOne(db, key: box.locationID.uuidString) != nil else {
                throw RepositoryError.unknownLocation
            }
            try Self.requireFreeSerial(db, for: box)
            do {
                try BoxRecord(box).insert(db)
            } catch let error as DatabaseError where error.extendedResultCode == .SQLITE_CONSTRAINT_UNIQUE {
                throw RepositoryError.duplicateSerial
            }
        }
    }

    func update(_ box: Box) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard let stored = try Self.liveRecord(db, box.id) else { throw RepositoryError.notFound }
            guard try LocationRecord.fetchOne(db, key: box.locationID.uuidString) != nil else {
                throw RepositoryError.unknownLocation
            }
            try Self.requireFreeSerial(db, for: box)
            var record = BoxRecord(box)
            record.createdAt = stored.createdAt
            record.removalReason = stored.removalReason
            record.removedAt = stored.removedAt
            record.updatedAt = now
            do {
                try record.update(db)
            } catch let error as DatabaseError where error.extendedResultCode == .SQLITE_CONSTRAINT_UNIQUE {
                throw RepositoryError.duplicateSerial
            }
        }
    }

    func markRemoved(id: Box.ID, reason: RemovalReason, at date: Date) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var record = try Self.liveRecord(db, id) else { throw RepositoryError.notFound }
            record.removalReason = reason.rawValue
            record.removedAt = date
            record.updatedAt = now
            try record.update(db)
        }
    }

    func undoRemoval(id: Box.ID) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var record = try Self.liveRecord(db, id) else { throw RepositoryError.notFound }
            record.removalReason = nil
            record.removedAt = nil
            record.updatedAt = now
            try record.update(db)
        }
    }

    func delete(id: Box.ID) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var record = try Self.liveRecord(db, id) else { throw RepositoryError.notFound }
            record.deletedAt = now
            record.updatedAt = now
            try record.update(db)
        }
    }

    func undoDelete(id: Box.ID) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var record = try BoxRecord.fetchOne(db, key: id.uuidString), record.deletedAt != nil else {
                throw RepositoryError.notFound
            }
            record.deletedAt = nil
            record.updatedAt = now
            do {
                try record.update(db)
            } catch let error as DatabaseError where error.extendedResultCode == .SQLITE_CONSTRAINT_UNIQUE {
                throw RepositoryError.duplicateSerial
            }
        }
    }

    private static func liveRecord(_ db: Database, _ id: Box.ID) throws -> BoxRecord? {
        try BoxRecord.fetchOne(db, key: id.uuidString).flatMap { $0.deletedAt == nil ? $0 : nil }
    }

    /// Checked inside the write transaction; the unique index is the backstop.
    private static func requireFreeSerial(_ db: Database, for box: Box) throws {
        guard let gtin = box.product.gtin, let serial = box.serial else { return }
        let taken = try BoxRecord.filter(
            Column("gtin") == gtin && Column("serial") == serial && Column("deletedAt") == nil
                && Column("id") != box.id.uuidString
        ).isEmpty(db) == false
        if taken { throw RepositoryError.duplicateSerial }
    }
}
