import Foundation
import GRDB
import Testing
@testable import MediCabinet

/// Schema v2: the box tombstone (`deletedAt`) and the unique serial among boxes that are not deleted.
@Suite struct MigrationV2Tests {
    let database: AppDatabase

    init() throws {
        database = try AppDatabase.inMemory()
    }

    private func insertLocation(_ db: Database, id: String = "L") throws {
        try db.execute(
            sql: "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) VALUES (?, 'Cabinet', 0, 0, ?, ?)",
            arguments: [id, Date(), Date()]
        )
    }

    private func insertBox(_ db: Database, id: String = UUID().uuidString, gtin: String?, serial: String?, deletedAt: Date? = nil) throws {
        try db.execute(
            sql: """
                INSERT INTO box (id, gtin, productName, locationId, serial, createdAt, updatedAt, deletedAt)
                VALUES (?, ?, 'Doliprane', 'L', ?, ?, ?, ?)
                """,
            arguments: [id, gtin, serial, Date(), Date(), deletedAt]
        )
    }

    /// A v1 box row (the table has no `deletedAt` yet).
    private func insertV1Box(_ db: Database, id: String, gtin: String, serial: String) throws {
        try db.execute(
            sql: """
                INSERT INTO box (id, gtin, productName, locationId, serial, createdAt, updatedAt)
                VALUES (?, ?, 'Doliprane', 'L', ?, ?, ?)
                """,
            arguments: [id, gtin, serial, Date(), Date()]
        )
    }

    @Test func v2AddsTheDeletedAtColumnAndANullDefault() throws {
        try database.writer.write { db in
            try insertLocation(db)
            try insertBox(db, gtin: "03400930000001", serial: "S1")
            #expect(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM box WHERE deletedAt IS NULL") == 1)
        }
    }

    @Test func theSameGtinAndSerialCannotBeStoredTwice() throws {
        #expect(throws: DatabaseError.self) {
            try database.writer.write { db in
                try insertLocation(db)
                try insertBox(db, gtin: "03400930000001", serial: "S1")
                try insertBox(db, gtin: "03400930000001", serial: "S1")
            }
        }
    }

    @Test func aTombstoneFreesTheSerialAndOtherCombinationsAreAllowed() throws {
        try database.writer.write { db in
            try insertLocation(db)
            try insertBox(db, gtin: "03400930000001", serial: "S1", deletedAt: Date())
            try insertBox(db, gtin: "03400930000001", serial: "S1")
            try insertBox(db, gtin: "03400930000002", serial: "S1")
            try insertBox(db, gtin: "03400930000001", serial: "S2")
            try insertBox(db, gtin: "03400930000001", serial: nil)
            try insertBox(db, gtin: "03400930000001", serial: nil)
            try insertBox(db, gtin: nil, serial: "S1")
            try insertBox(db, gtin: nil, serial: "S1")
            #expect(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM box") == 8)
        }
    }

    @Test func upgradingAV1CabinetKeepsEveryRow() throws {
        let queue = try DatabaseQueue()
        try Migrations.migrator.migrate(queue, upTo: Migrations.v1)
        try queue.write { db in
            try insertLocation(db)
            // v1 allowed the same serial twice (nothing could add a box yet, but the schema allowed it for other GTINs).
            try insertV1Box(db, id: "A", gtin: "03400930000001", serial: "S1")
            try insertV1Box(db, id: "B", gtin: "03400930000002", serial: "S1")
        }
        try Migrations.migrator.migrate(queue)
        let (ids, tombstones, applied) = try queue.read { db in
            (
                try String.fetchAll(db, sql: "SELECT id FROM box ORDER BY id"),
                try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM box WHERE deletedAt IS NOT NULL"),
                try Migrations.migrator.appliedIdentifiers(db)
            )
        }
        #expect(ids == ["A", "B"])
        #expect(tombstones == 0)
        #expect(applied == Set(Migrations.identifiers))
    }

    @Test func aV1CabinetWithDuplicateSerialsFailsToUpgradeAndStaysOnV1() throws {
        let queue = try DatabaseQueue()
        try Migrations.migrator.migrate(queue, upTo: Migrations.v1)
        try queue.write { db in
            try insertLocation(db)
            try insertV1Box(db, id: "A", gtin: "03400930000001", serial: "S1")
            try insertV1Box(db, id: "B", gtin: "03400930000001", serial: "S1")
        }
        #expect(throws: DatabaseError.self) { try Migrations.migrator.migrate(queue) }
        let (ids, applied, hasTombstone) = try queue.read { db in
            (
                try String.fetchAll(db, sql: "SELECT id FROM box ORDER BY id"),
                try Migrations.migrator.appliedIdentifiers(db),
                try db.columns(in: "box").contains { $0.name == "deletedAt" }
            )
        }
        #expect(ids == ["A", "B"])
        #expect(applied == [Migrations.v1])
        #expect(!hasTombstone)
    }
}
