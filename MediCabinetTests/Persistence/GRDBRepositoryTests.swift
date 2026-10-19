import Foundation
import GRDB
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@Suite struct GRDBRepositoryTests {
    /// Runs the shared repository contract against the real GRDB implementation on a temp DB.
    @Test(arguments: RepositoryContract.checks)
    func grdbSatisfiesContract(check: RepositoryContract.Check) async throws {
        try await check.run { clock in
            let (database, _) = try AppDatabase.temporary()
            return (
                GRDBLocationRepository(database: database, clock: clock),
                GRDBBoxRepository(database: database, clock: clock)
            )
        }
    }

    private struct Setup {
        let database: AppDatabase
        let locations: GRDBLocationRepository
        let boxes: GRDBBoxRepository
        let clock: MutableClock

        init() throws {
            database = try AppDatabase.inMemory()
            clock = MutableClock(Date(timeIntervalSince1970: 1_800_000_000))
            locations = GRDBLocationRepository(database: database, clock: clock)
            boxes = GRDBBoxRepository(database: database, clock: clock)
        }
    }

    private struct StoredExpiry: Sendable {
        let year: Int?, month: Int?, day: Int?, sortKey: String?
    }

    @Test func expiryIsStoredAsPartsAndSortKeyWithMonthPrecisionLeavingDayNull() async throws {
        let setup = try Setup()
        let (database, locations, boxes, clock) = (setup.database, setup.locations, setup.boxes, setup.clock)
        let location = try await locations.ensureDefault(named: "Cabinet")
        let box = Box(
            product: ProductRef(name: "Doliprane"), locationID: location.id,
            expiry: try ExpiryDate.make(year: 2028, month: 2, day: nil), createdAt: clock.now
        )
        try await boxes.insert(box)
        let stored = try await database.writer.read { db -> StoredExpiry? in
            guard let row = try Row.fetchOne(db, sql: "SELECT expiryYear, expiryMonth, expiryDay, expirySortKey FROM box") else {
                return nil
            }
            return StoredExpiry(year: row["expiryYear"], month: row["expiryMonth"], day: row["expiryDay"], sortKey: row["expirySortKey"])
        }
        #expect(stored?.year == 2028)
        #expect(stored?.month == 2)
        #expect(stored?.day == nil)
        #expect(stored?.sortKey == "2028-02-29")
    }

    @Test func idsAreStoredAsTextUuids() async throws {
        let setup = try Setup()
        let (database, locations) = (setup.database, setup.locations)
        let location = try await locations.ensureDefault(named: "Cabinet")
        let stored = try await database.writer.read { try String.fetchOne($0, sql: "SELECT id FROM location") }
        #expect(stored == location.id.uuidString)
    }

    @Test func aCorruptStoredExpiryIsReportedNotRepaired() async throws {
        let setup = try Setup()
        let (database, locations, boxes, clock) = (setup.database, setup.locations, setup.boxes, setup.clock)
        let location = try await locations.ensureDefault(named: "Cabinet")
        let box = Box(product: ProductRef(name: "A"), locationID: location.id, createdAt: clock.now)
        try await boxes.insert(box)
        // Year outside the supported range passes SQL constraints but is not a valid ExpiryDate.
        try await database.writer.write { db in
            try db.execute(sql: "UPDATE box SET expiryYear = 1850, expiryMonth = 1, expirySortKey = '1850-01-31'")
        }
        await #expect(throws: PersistenceError.corruptRow(table: "box")) { try await boxes.box(id: box.id) }
    }

    @Test func aFailedInsertLeavesNothingBehind() async throws {
        let setup = try Setup()
        let (database, locations, boxes, clock) = (setup.database, setup.locations, setup.boxes, setup.clock)
        let location = try await locations.ensureDefault(named: "Cabinet")
        let oversized = Box(
            product: ProductRef(name: String(repeating: "x", count: 201)), locationID: location.id, createdAt: clock.now
        )
        await #expect(throws: DatabaseError.self) { try await boxes.insert(oversized) }
        #expect(try await database.writer.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM box") } == 0)
    }

    @Test func removedBoxesStayInTheTableWithTheirReason() async throws {
        let setup = try Setup()
        let (database, locations, boxes, clock) = (setup.database, setup.locations, setup.boxes, setup.clock)
        let location = try await locations.ensureDefault(named: "Cabinet")
        let box = Box(product: ProductRef(name: "A"), locationID: location.id, createdAt: clock.now)
        try await boxes.insert(box)
        try await boxes.markRemoved(id: box.id, reason: .thrownAway, at: clock.now)
        let reason = try await database.writer.read { try String.fetchOne($0, sql: "SELECT removalReason FROM box") }
        #expect(reason == "thrownAway")
    }
}
