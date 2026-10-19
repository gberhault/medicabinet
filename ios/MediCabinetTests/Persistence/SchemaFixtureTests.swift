import Foundation
import GRDB
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

/// Compares the migrated iOS database with `fixtures/schema-v1.json`, which the Android
/// database is compared with too — so the two schemas cannot drift.
struct SchemaSnapshot: Codable, Equatable, Sendable {
    struct Column: Codable, Equatable, Sendable {
        var name: String
        var type: String
        var notNull: Bool
        var `default`: String?
        var primaryKey: Bool
    }
    struct ForeignKey: Codable, Equatable, Sendable {
        var from: String
        var table: String
        var target: String
        var onDelete: String

        private enum CodingKeys: String, CodingKey {
            case from, table, target = "to", onDelete
        }
    }
    struct Table: Codable, Equatable, Sendable {
        var columns: [Column]
        var foreignKeys: [ForeignKey]
    }
    struct Index: Codable, Equatable, Sendable {
        var name: String
        var table: String
        var columns: [String]
        var unique: Bool
        var `where`: String?
    }

    var tables: [String: Table]
    var indexes: [Index]

    /// Introspects a database with the same pragmas the fixture was generated from.
    static func read(from db: Database) throws -> SchemaSnapshot {
        let names = try String.fetchAll(
            db,
            sql: """
                SELECT name FROM sqlite_master
                WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'grdb_%' ORDER BY name
                """
        )
        var tables: [String: Table] = [:]
        var indexes: [Index] = []
        for name in names {
            let columns = try Row.fetchAll(db, sql: "PRAGMA table_info(\(name))").map { row in
                Column(
                    name: row["name"], type: row["type"], notNull: (row["notnull"] as Int) == 1,
                    default: row["dflt_value"], primaryKey: (row["pk"] as Int) > 0
                )
            }
            let foreignKeys = try Row.fetchAll(db, sql: "PRAGMA foreign_key_list(\(name))").map { row in
                ForeignKey(from: row["from"], table: row["table"], target: row["to"], onDelete: row["on_delete"])
            }.sorted { $0.from < $1.from }
            tables[name] = Table(columns: columns, foreignKeys: foreignKeys)

            for row in try Row.fetchAll(db, sql: "PRAGMA index_list(\(name))") where (row["origin"] as String) == "c" {
                let indexName: String = row["name"]
                let indexColumns = try Row.fetchAll(db, sql: "PRAGMA index_info(\(indexName))").map { $0["name"] as String }
                let ddl = try String.fetchOne(
                    db, sql: "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?", arguments: [indexName]
                ) ?? ""
                let whereClause = ddl.range(of: " WHERE ").map {
                    String(ddl[$0.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
                }
                indexes.append(Index(
                    name: indexName, table: name, columns: indexColumns,
                    unique: (row["unique"] as Int) == 1, where: whereClause
                ))
            }
        }
        return SchemaSnapshot(tables: tables, indexes: indexes.sorted { $0.name < $1.name })
    }
}

@Suite struct SchemaFixtureTests {
    private func fixture() throws -> SchemaSnapshot {
        try JSONDecoder().decode(SchemaSnapshot.self, from: SharedFixtures.data("schema-v1.json"))
    }

    private func migrated() throws -> SchemaSnapshot {
        try AppDatabase.inMemory().writer.read { try SchemaSnapshot.read(from: $0) }
    }

    @Test func theMigratedDatabaseMatchesTheSharedSchemaFixture() throws {
        #expect(try migrated() == fixture())
    }

    @Test func aFixtureWithAMissingIndexDoesNotMatch() throws {
        var altered = try fixture()
        altered.indexes.removeAll { $0.name == "box_gtin" }
        #expect(try migrated() != altered)
    }

    @Test func aFixtureWithAChangedColumnDoesNotMatch() throws {
        var altered = try fixture()
        altered.tables["box"]?.columns[0].notNull.toggle()
        #expect(try migrated() != altered)
    }

    @Test func theFixtureCoversBothTablesAndEveryLookupIndex() throws {
        let snapshot = try fixture()
        #expect(Set(snapshot.tables.keys) == ["location", "box"])
        #expect(Set(snapshot.indexes.map(\.name)) == [
            "location_single_default", "box_gtin", "box_location", "box_expiry", "box_active", "box_serial"
        ])
        #expect(snapshot.indexes.first { $0.name == "location_single_default" }?.unique == true)
        #expect(snapshot.indexes.first { $0.name == "box_serial" }?.where == "serial IS NOT NULL")
    }
}
