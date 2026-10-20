import Foundation
import GRDB
import MediCabinetCore

struct GRDBNamedProductRepository: NamedProductRepository {
    /// The `cabinetMeta` key and value of a fill that has not run yet.
    static let backfillKey = "namedProductBackfill"
    static let pending = "pending"

    let database: AppDatabase
    let clock: any Clock

    /// Products that are not forgotten.
    private static var live: QueryInterfaceRequest<NamedProductRecord> {
        NamedProductRecord.filter(Column("deletedAt") == nil)
    }

    private static func liveRecord(_ db: Database, _ id: NamedProduct.ID) throws -> NamedProductRecord? {
        try live.filter(Column("id") == id.uuidString).fetchOne(db)
    }

    func namedProduct(gtin: String) async throws -> NamedProduct? {
        try await database.writer.read { db in
            try Self.live.filter(Column("gtin") == gtin).fetchOne(db)?.model()
        }
    }

    func remember(gtin: String, name: String, category: CategoryID, categoryOverridden: Bool) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var stored = try Self.live.filter(Column("gtin") == gtin).fetchOne(db) else {
                try NamedProductRecord(NamedProduct(
                    gtin: gtin, name: name, category: category, categoryOverridden: categoryOverridden, createdAt: now
                )).insert(db)
                return
            }
            guard stored.name != name || stored.category != category.rawValue || stored.categoryOverridden != categoryOverridden
            else { return }
            stored.name = name
            stored.category = category.rawValue
            stored.categoryOverridden = categoryOverridden
            stored.updatedAt = now
            try stored.update(db)
        }
    }

    /// One query: the live products with the count of their boxes in the cabinet, sorted in the core (the SQL
    /// cannot compare names as the normalized text the Android twin compares).
    func observeSummaries() -> AsyncThrowingStream<[NamedProductSummary], any Error> {
        let observation = ValueObservation.tracking { db in try Self.summaries(db) }
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for try await summaries in observation.values(in: database.writer) { continuation.yield(summaries) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func rename(id: NamedProduct.ID, to name: String) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var stored = try Self.liveRecord(db, id) else { throw RepositoryError.notFound }
            stored.name = name
            stored.updatedAt = now
            try stored.update(db)
            try db.execute(
                sql: "UPDATE box SET productName = ?, updatedAt = ? WHERE gtin = ? AND deletedAt IS NULL",
                arguments: [name, now, stored.gtin]
            )
        }
    }

    func setCategory(id: NamedProduct.ID, _ category: CategoryID, overridden: Bool) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var stored = try Self.liveRecord(db, id) else { throw RepositoryError.notFound }
            stored.category = category.rawValue
            stored.categoryOverridden = overridden
            stored.updatedAt = now
            try stored.update(db)
            try db.execute(
                sql: "UPDATE box SET category = ?, categoryOverridden = ?, updatedAt = ? WHERE gtin = ? AND deletedAt IS NULL",
                arguments: [category.rawValue, overridden, now, stored.gtin]
            )
        }
    }

    func forget(id: NamedProduct.ID) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var stored = try Self.liveRecord(db, id) else { throw RepositoryError.notFound }
            stored.deletedAt = now
            stored.updatedAt = now
            try stored.update(db)
        }
    }

    func undoForget(id: NamedProduct.ID) async throws {
        let now = clock.now
        try await database.writer.write { db in
            guard var stored = try NamedProductRecord.fetchOne(db, key: id.uuidString), stored.deletedAt != nil else {
                throw RepositoryError.notFound
            }
            if try Self.live.filter(Column("gtin") == stored.gtin).fetchCount(db) > 0 { throw RepositoryError.duplicate }
            stored.deletedAt = nil
            stored.updatedAt = now
            do {
                try stored.update(db)
            } catch let error as DatabaseError where error.extendedResultCode == .SQLITE_CONSTRAINT_UNIQUE {
                throw RepositoryError.duplicate
            }
        }
    }

    func backfillPending() async throws -> Bool {
        try await database.writer.read { db in
            try String.fetchOne(db, sql: "SELECT value FROM cabinetMeta WHERE key = ?", arguments: [Self.backfillKey])
                == Self.pending
        }
    }

    /// The products and the cleared mark are one transaction. A GTIN that already has a live product keeps it.
    func completeBackfill(_ products: [NamedProduct]) async throws {
        try await database.writer.write { db in
            for product in products where try Self.live.filter(Column("gtin") == product.gtin).fetchCount(db) == 0 {
                try NamedProductRecord(product).insert(db)
            }
            try db.execute(sql: "DELETE FROM cabinetMeta WHERE key = ?", arguments: [Self.backfillKey])
        }
    }

    private static func summaries(_ db: Database) throws -> [NamedProductSummary] {
        let rows = try Row.fetchAll(db, sql: """
            SELECT n.*, COALESCE(c.inCabinet, 0) AS inCabinet
            FROM namedProduct n
            LEFT JOIN (
                SELECT gtin, COUNT(*) AS inCabinet FROM box
                WHERE removedAt IS NULL AND deletedAt IS NULL AND gtin IS NOT NULL GROUP BY gtin
            ) c ON c.gtin = n.gtin
            WHERE n.deletedAt IS NULL
            """)
        let summaries = try rows.map { row in
            NamedProductSummary(product: try NamedProductRecord(row: row).model(), boxesInCabinet: row["inCabinet"])
        }
        return NamedProductOrder.sorted(summaries)
    }
}
