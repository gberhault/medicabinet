import Foundation
import GRDB
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@Suite struct GRDBNamedProductRepositoryTests {
    private let clock = MutableClock(Date(timeIntervalSince1970: 1_800_000_000))
    private let database: AppDatabase
    private let named: GRDBNamedProductRepository
    private let boxes: GRDBBoxRepository
    private let locations: GRDBLocationRepository

    init() throws {
        database = try AppDatabase.inMemory()
        named = GRDBNamedProductRepository(database: database, clock: clock)
        boxes = GRDBBoxRepository(database: database, clock: clock)
        locations = GRDBLocationRepository(database: database, clock: clock)
    }

    private func count(_ sql: String) async throws -> Int {
        try await database.writer.read { try Int.fetchOne($0, sql: sql) } ?? -1
    }

    @Test func aNewDatabaseHasThePendingMarkerAndClearingItDeletesTheRow() async throws {
        #expect(try await named.backfillPending())
        try await named.completeBackfill([])
        #expect(try await count("SELECT COUNT(*) FROM cabinetMeta") == 0)
        #expect(try await named.backfillPending() == false)
    }

    @Test func aFailingFillWritesNothingAndKeepsTheFillPending() async throws {
        let good = NamedProduct(gtin: "03400930000001", name: "Arnica", createdAt: clock.now)
        let bad = NamedProduct(gtin: "123", name: "Not a gtin", createdAt: clock.now)
        await #expect(throws: DatabaseError.self) { try await named.completeBackfill([good, bad]) }
        #expect(try await count("SELECT COUNT(*) FROM namedProduct") == 0)
        #expect(try await named.backfillPending())
    }

    @Test func aFillSkipsASecondProductWithTheSameGTIN() async throws {
        let first = NamedProduct(gtin: "03400930000001", name: "First", createdAt: clock.now)
        let second = NamedProduct(gtin: "03400930000001", name: "Second", createdAt: clock.now)
        try await named.completeBackfill([first, second])
        #expect(try await named.namedProduct(gtin: "03400930000001")?.name == "First")
    }

    @Test func aRefusedRenameChangesNeitherTheProductNorItsBoxes() async throws {
        let location = try await locations.ensureDefault(named: "Cabinet")
        let box = Box(product: ProductRef(gtin: "03400930000001", name: "Test"), locationID: location.id, createdAt: clock.now)
        try await boxes.insert(box)
        try await named.remember(gtin: "03400930000001", name: "Test", category: .uncategorized, categoryOverridden: false)
        let id = try #require(try await named.namedProduct(gtin: "03400930000001")?.id)
        // 201 characters break the table's CHECK: the whole rename is refused.
        await #expect(throws: DatabaseError.self) { try await named.rename(id: id, to: String(repeating: "x", count: 201)) }
        #expect(try await named.namedProduct(gtin: "03400930000001")?.name == "Test")
        #expect(try await boxes.box(id: box.id)?.product.name == "Test")
    }

    @Test func idsAndDatesAreStoredAsTheOtherTablesStoreThem() async throws {
        try await named.remember(gtin: "03400930000001", name: "Test", category: .uncategorized, categoryOverridden: true)
        let stored = try await database.writer.read { try String.fetchOne($0, sql: "SELECT id FROM namedProduct") }
        #expect(stored == (try await named.namedProduct(gtin: "03400930000001"))?.id.uuidString)
        #expect(try await count("SELECT COUNT(*) FROM namedProduct WHERE categoryOverridden = 1") == 1)
    }

    @Test func aCorruptStoredIdIsReportedNotRepaired() async throws {
        try await database.writer.write { db in
            try db.execute(sql: """
                INSERT INTO namedProduct (id, gtin, name, createdAt, updatedAt)
                VALUES ('not-a-uuid', '03400930000001', 'X', '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')
                """)
        }
        await #expect(throws: PersistenceError.corruptRow(table: "namedProduct")) {
            try await named.namedProduct(gtin: "03400930000001")
        }
    }
}
