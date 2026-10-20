import Foundation
import GRDB
import Testing
@testable import MediCabinet

@Suite struct MigrationTests {
    let database: AppDatabase

    init() throws {
        database = try AppDatabase.inMemory()
    }

    private func names(of kind: String) throws -> Set<String> {
        try database.writer.read { db in
            Set(try String.fetchAll(
                db,
                sql: "SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE 'sqlite_%'",
                arguments: [kind]
            ))
        }
    }

    private func insertLocation(_ db: Database, id: String = UUID().uuidString, name: String = "Cabinet", isDefault: Int = 0) throws {
        try db.execute(
            sql: "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) VALUES (?, ?, ?, 0, ?, ?)",
            arguments: [id, name, isDefault, Date(), Date()]
        )
    }

    private func insertBox(
        _ db: Database, locationID: String, gtin: String? = "03400930000001",
        year: Int? = nil, month: Int? = nil, day: Int? = nil, sortKey: String? = nil,
        removalReason: String? = nil, removedAt: Date? = nil
    ) throws {
        try db.execute(
            sql: """
                INSERT INTO box (id, gtin, productName, locationId, expiryYear, expiryMonth, expiryDay, expirySortKey,
                                 removalReason, removedAt, createdAt, updatedAt)
                VALUES (?, ?, 'Doliprane', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            arguments: [UUID().uuidString, gtin, locationID, year, month, day, sortKey, removalReason, removedAt, Date(), Date()]
        )
    }

    @Test func v1CreatesTablesAndIndexes() throws {
        #expect(try names(of: "table") == ["location", "box", "grdb_migrations"])
        #expect(try names(of: "index").isSuperset(of: [
            "location_single_default", "box_gtin", "box_location", "box_expiry", "box_active", "box_serial"
        ]))
    }

    @Test func migrationsAreRecordedAndIdempotent() throws {
        let applied = try database.writer.read { db in try Migrations.migrator.appliedIdentifiers(db) }
        #expect(applied == Set(Migrations.identifiers))
        try Migrations.migrator.migrate(database.writer)
        #expect(try database.writer.read { db in try Migrations.migrator.appliedIdentifiers(db) } == Set(Migrations.identifiers))
    }

    @Test func aValidBoxIsAccepted() throws {
        try database.writer.write { db in
            try insertLocation(db, id: "L")
            try insertBox(db, locationID: "L", year: 2027, month: 3, day: 15, sortKey: "2027-03-15")
            try insertBox(db, locationID: "L", year: 2027, month: 3, sortKey: "2027-03-31")
            try insertBox(db, locationID: "L")
            #expect(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM box") == 3)
        }
    }

    @Test func emptyLocationNameIsRejected() throws {
        #expect(throws: DatabaseError.self) { try database.writer.write { try insertLocation($0, name: "") } }
    }

    @Test func aSecondDefaultLocationIsRejected() throws {
        #expect(throws: DatabaseError.self) {
            try database.writer.write { db in
                try insertLocation(db, name: "A", isDefault: 1)
                try insertLocation(db, name: "B", isDefault: 1)
            }
        }
    }

    @Test func manyNonDefaultLocationsAreAllowed() throws {
        try database.writer.write { db in
            try insertLocation(db, name: "A")
            try insertLocation(db, name: "B")
            try insertLocation(db, name: "C", isDefault: 1)
        }
    }

    struct InvalidExpiry: Sendable, CustomTestStringConvertible {
        let label: String
        let year: Int
        let month: Int
        let day: Int?
        let sortKey: String
        var testDescription: String { label }
    }

    @Test(arguments: [
        InvalidExpiry(label: "month 13", year: 2027, month: 13, day: nil, sortKey: "2027-13-01"),
        InvalidExpiry(label: "month 0", year: 2027, month: 0, day: nil, sortKey: "2027-00-01"),
        InvalidExpiry(label: "day 32", year: 2027, month: 1, day: 32, sortKey: "2027-01-32")
    ])
    func invalidExpiryPartsAreRejected(_ expiry: InvalidExpiry) throws {
        #expect(throws: DatabaseError.self) {
            try database.writer.write { db in
                try insertLocation(db, id: "L")
                try insertBox(db, locationID: "L", year: expiry.year, month: expiry.month, day: expiry.day, sortKey: expiry.sortKey)
            }
        }
    }

    @Test func halfFilledExpiryIsRejected() throws {
        struct Half { let year: Int?, month: Int?, sortKey: String? }
        let cases = [
            Half(year: 2027, month: nil, sortKey: "2027-01-01"),
            Half(year: nil, month: 3, sortKey: nil),
            Half(year: 2027, month: 3, sortKey: nil)
        ]
        for half in cases {
            #expect(throws: DatabaseError.self) {
                try database.writer.write { db in
                    try insertLocation(db, id: "L")
                    try insertBox(db, locationID: "L", year: half.year, month: half.month, sortKey: half.sortKey)
                }
            }
        }
    }

    @Test func removalReasonWithoutDateAndDateWithoutReasonAreRejected() throws {
        #expect(throws: DatabaseError.self) {
            try database.writer.write { db in
                try insertLocation(db, id: "L")
                try insertBox(db, locationID: "L", removalReason: "usedUp", removedAt: nil)
            }
        }
        #expect(throws: DatabaseError.self) {
            try database.writer.write { db in
                try insertLocation(db, id: "L")
                try insertBox(db, locationID: "L", removalReason: nil, removedAt: Date())
            }
        }
    }

    @Test func unknownRemovalReasonIsRejected() throws {
        #expect(throws: DatabaseError.self) {
            try database.writer.write { db in
                try insertLocation(db, id: "L")
                try insertBox(db, locationID: "L", removalReason: "lost", removedAt: Date())
            }
        }
    }

    @Test func gtinMustBeFourteenDigits() throws {
        #expect(throws: DatabaseError.self) {
            try database.writer.write { db in
                try insertLocation(db, id: "L")
                try insertBox(db, locationID: "L", gtin: "3400930000001")
            }
        }
    }

    @Test func aBoxNeedsAnExistingLocation() throws {
        #expect(throws: DatabaseError.self) {
            try database.writer.write { try insertBox($0, locationID: "missing") }
        }
    }

    @Test func aLocationWithBoxesCannotBeDeleted() throws {
        try database.writer.write { db in
            try insertLocation(db, id: "L")
            try insertBox(db, locationID: "L")
        }
        #expect(throws: DatabaseError.self) {
            try database.writer.write { try $0.execute(sql: "DELETE FROM location WHERE id = 'L'") }
        }
    }

    @Test(arguments: [
        ("SELECT * FROM box WHERE removedAt IS NULL", "box_active"),
        ("SELECT * FROM box WHERE gtin = '03400930000001'", "box_gtin"),
        ("SELECT * FROM box WHERE locationId = 'L'", "box_location"),
        ("SELECT * FROM box ORDER BY expirySortKey", "box_expiry")
    ])
    func hotQueriesUseAnIndex(sql: String, index: String) throws {
        let plan = try database.writer.read { db in
            try Row.fetchAll(db, sql: "EXPLAIN QUERY PLAN " + sql).map { $0["detail"] as String }.joined(separator: " | ")
        }
        #expect(plan.contains(index), "plan was: \(plan)")
    }
}
