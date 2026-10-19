import Foundation
import GRDB
import Testing
@testable import MediCabinet

/// Schema v3: the location tombstone (`deletedAt`).
@Suite struct MigrationV3Tests {
    let database: AppDatabase

    init() throws {
        database = try AppDatabase.inMemory()
    }

    private func insertLocation(_ db: Database, id: String, name: String, isDefault: Bool = false, deletedAt: Date? = nil) throws {
        try db.execute(
            sql: """
                INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt, deletedAt)
                VALUES (?, ?, ?, 0, ?, ?, ?)
                """,
            arguments: [id, name, isDefault, Date(), Date(), deletedAt]
        )
    }

    @Test func theIdentifiersListEveryRegisteredMigrationInOrder() {
        #expect(Migrations.migrator.migrations == Migrations.identifiers)
        #expect(Migrations.identifiers.last == Migrations.schemaV3)
    }

    @Test func v3AddsANullableDeletedAtColumnToLocations() throws {
        try database.writer.write { db in
            try insertLocation(db, id: "A", name: "Cabinet")
            try insertLocation(db, id: "B", name: "Garage", deletedAt: Date())
            let column = try #require(try db.columns(in: "location").first { $0.name == "deletedAt" })
            #expect(!column.isNotNull)
            #expect(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM location WHERE deletedAt IS NULL") == 1)
            #expect(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM location WHERE deletedAt IS NOT NULL") == 1)
        }
    }

    @Test func theSingleDefaultIndexIsUnchanged() throws {
        #expect(throws: DatabaseError.self) {
            try database.writer.write { db in
                try insertLocation(db, id: "A", name: "One", isDefault: true)
                try insertLocation(db, id: "B", name: "Two", isDefault: true, deletedAt: Date())
            }
        }
    }

    @Test func aDeletedLocationKeepsItsRemovedBoxesThroughTheForeignKey() throws {
        try database.writer.write { db in
            try insertLocation(db, id: "A", name: "Cabinet", isDefault: true)
            try insertLocation(db, id: "B", name: "Garage", deletedAt: Date())
            try db.execute(
                sql: """
                    INSERT INTO box (id, productName, locationId, removalReason, removedAt, createdAt, updatedAt)
                    VALUES ('X', 'Doliprane', 'B', 'usedUp', ?, ?, ?)
                    """,
                arguments: [Date(), Date(), Date()]
            )
            #expect(try String.fetchOne(db, sql: "SELECT locationId FROM box WHERE id = 'X'") == "B")
        }
    }

    @Test func upgradingAV2CabinetKeepsEveryLocationAndBox() throws {
        let queue = try DatabaseQueue()
        try Migrations.migrator.migrate(queue, upTo: Migrations.schemaV2)
        try queue.write { db in
            try db.execute(
                sql: "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) VALUES ('A', 'Cabinet', 1, 0, ?, ?)",
                arguments: [Date(), Date()]
            )
            try db.execute(
                sql: "INSERT INTO box (id, productName, locationId, createdAt, updatedAt) VALUES ('X', 'Doliprane', 'A', ?, ?)",
                arguments: [Date(), Date()]
            )
        }
        try Migrations.migrator.migrate(queue)
        let (names, deleted, boxes, applied) = try queue.read { db in
            (
                try String.fetchAll(db, sql: "SELECT name FROM location ORDER BY name"),
                try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM location WHERE deletedAt IS NOT NULL"),
                try String.fetchAll(db, sql: "SELECT id FROM box"),
                try Migrations.migrator.appliedIdentifiers(db)
            )
        }
        #expect(names == ["Cabinet"])
        #expect(deleted == 0)
        #expect(boxes == ["X"])
        #expect(applied == Set(Migrations.identifiers))
    }
}
