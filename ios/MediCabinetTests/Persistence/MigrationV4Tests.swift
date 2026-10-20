import Foundation
import GRDB
import Testing
@testable import MediCabinet

/// Schema v4: the named products and the backfill marker (`namedProduct`, `cabinetMeta`).
@Suite struct MigrationV4Tests {
    private func named(
        _ db: Database, id: String, gtin: String = "03401900000003", name: String = "Arnica", deletedAt: Date? = nil
    ) throws {
        try db.execute(
            sql: """
                INSERT INTO namedProduct (id, gtin, name, createdAt, updatedAt, deletedAt)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            arguments: [id, gtin, name, Date(), Date(), deletedAt]
        )
    }

    private func scalar<T: DatabaseValueConvertible & StatementColumnConvertible>(
        _ db: any DatabaseReader, _ sql: String
    ) throws -> T? {
        try db.read { try T.fetchOne($0, sql: sql) }
    }

    private static let backfillMarker = "SELECT value FROM cabinetMeta WHERE key = 'namedProductBackfill'"

    @Test func aNewDatabaseHasEmptyNamedProductsAndAPendingBackfill() throws {
        let database = try AppDatabase.inMemory()
        let count: Int? = try scalar(database.writer, "SELECT COUNT(*) FROM namedProduct")
        let marker: String? = try scalar(database.writer, Self.backfillMarker)
        #expect(count == 0)
        #expect(marker == "pending")
    }

    @Test func upgradingAV3CabinetKeepsEveryLocationAndBoxAndMarksTheBackfillPending() throws {
        let queue = try DatabaseQueue()
        try Migrations.migrator.migrate(queue, upTo: Migrations.schemaV3)
        try queue.write { db in
            try db.execute(
                sql: "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) VALUES ('A', 'Cabinet', 1, 0, ?, ?)",
                arguments: [Date(), Date()]
            )
            try db.execute(
                sql: """
                    INSERT INTO box (id, gtin, productName, locationId, createdAt, updatedAt)
                    VALUES ('X', '03401900000003', 'Arnica', 'A', ?, ?)
                    """,
                arguments: [Date(), Date()]
            )
        }
        try Migrations.migrator.migrate(queue)
        let location: String? = try scalar(queue, "SELECT name FROM location")
        let box: String? = try scalar(queue, "SELECT productName FROM box WHERE id = 'X'")
        let count: Int? = try scalar(queue, "SELECT COUNT(*) FROM namedProduct")
        let marker: String? = try scalar(queue, Self.backfillMarker)
        let applied = try queue.read { try Migrations.migrator.appliedIdentifiers($0) }
        #expect(location == "Cabinet")
        #expect(box == "Arnica")
        #expect(count == 0)
        #expect(marker == "pending")
        #expect(applied == Set(Migrations.identifiers))
    }

    @Test func aGTINMustBeFourteenCharactersAndANameOneToTwoHundred() throws {
        let database = try AppDatabase.inMemory()
        try database.writer.write { db in
            #expect(throws: DatabaseError.self) { try named(db, id: "A", gtin: "3401900000003") }
            #expect(throws: DatabaseError.self) { try named(db, id: "B", name: "") }
            #expect(throws: DatabaseError.self) { try named(db, id: "C", name: String(repeating: "x", count: 201)) }
            try named(db, id: "D", name: String(repeating: "x", count: 200))
        }
    }

    @Test func onlyOneLiveNamedProductPerGTINButAForgottenOneMayCoexist() throws {
        let database = try AppDatabase.inMemory()
        try database.writer.write { db in
            try named(db, id: "A")
            #expect(throws: DatabaseError.self) { try named(db, id: "B") }
            try named(db, id: "C", deletedAt: Date())
            try named(db, id: "D", deletedAt: Date())
            try named(db, id: "E", gtin: "03401900000010")
            let count = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM namedProduct")
            #expect(count == 4)
        }
    }

    @Test func theMetaKeyIsUnique() throws {
        let database = try AppDatabase.inMemory()
        try database.writer.write { db in
            #expect(throws: DatabaseError.self) {
                try db.execute(sql: "INSERT INTO cabinetMeta (key, value) VALUES ('namedProductBackfill', 'done')")
            }
        }
    }
}
