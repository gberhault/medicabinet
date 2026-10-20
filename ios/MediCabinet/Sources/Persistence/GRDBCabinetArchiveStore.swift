import Foundation
import GRDB
import MediCabinetCore

/// The whole cabinet for export and import (design §5.1): every location, box and named product, tombstones included.
/// `replace` runs in one write transaction, so the automatic backup taken by the hook is exactly the cabinet it
/// replaces and nothing can land in between (I5); any throw rolls everything back (I6).
struct GRDBCabinetArchiveStore: CabinetArchiveStore {
    let database: AppDatabase

    func snapshot() async throws -> CabinetContents {
        try await database.writer.read { db in try Self.contents(db) }
    }

    func replace(
        with snapshot: CabinetContents, beforeReplacing: @Sendable (CabinetContents) throws -> Void
    ) async throws {
        try Self.requireHoldable(snapshot)
        try await database.writer.write { db in
            try beforeReplacing(try Self.contents(db))
            // Boxes before locations: a location cannot go while a box points at it (ON DELETE RESTRICT). Named products
            // point at nothing.
            try db.execute(sql: "DELETE FROM namedProduct")
            try db.execute(sql: "DELETE FROM box")
            try db.execute(sql: "DELETE FROM location")
            do {
                for archived in snapshot.locations {
                    var record = LocationRecord(archived.location)
                    record.deletedAt = archived.deletedAt
                    try record.insert(db)
                }
                for archived in snapshot.boxes {
                    // The record recomputes the derived expiry sort key from the expiry parts (F5).
                    var record = BoxRecord(archived.box)
                    record.deletedAt = archived.deletedAt
                    try record.insert(db)
                }
                for archived in snapshot.namedProducts {
                    var record = NamedProductRecord(archived.product)
                    record.deletedAt = archived.deletedAt
                    try record.insert(db)
                }
            } catch let error as DatabaseError {
                throw Self.repositoryError(for: error)
            }
            try Self.writeBackfillMarker(db, pending: snapshot.namedProductsNeedFill)
        }
    }

    /// The backfill marker is state of the cabinet database (`cabinetMeta`), so it is replaced in the same transaction
    /// as the rows: pending when the archive came from a version 1 file (the products are filled from the boxes after
    /// the import), absent otherwise (the file's products are the household's).
    private static func writeBackfillMarker(_ db: Database, pending: Bool) throws {
        if pending {
            try db.execute(
                sql: "INSERT OR REPLACE INTO cabinetMeta (key, value) VALUES (?, ?)",
                arguments: [GRDBNamedProductRepository.backfillKey, GRDBNamedProductRepository.pending]
            )
        } else {
            try db.execute(sql: "DELETE FROM cabinetMeta WHERE key = ?", arguments: [GRDBNamedProductRepository.backfillKey])
        }
    }

    /// Rows in a stable order (locations by sort order, boxes by creation), so a snapshot reads the same every time.
    private static func contents(_ db: Database) throws -> CabinetContents {
        let locations = try LocationRecord.order(Column("sortOrder"), Column("id")).fetchAll(db).map { record in
            ArchivedLocation(location: exact(try record.model()), deletedAt: record.deletedAt.map(exact))
        }
        let boxes = try BoxRecord.order(Column("createdAt"), Column("id")).fetchAll(db).map { record in
            ArchivedBox(box: exact(try record.model()), deletedAt: record.deletedAt.map(exact))
        }
        let products = try NamedProductRecord.order(Column("createdAt"), Column("id")).fetchAll(db).map { record in
            ArchivedNamedProduct(product: exact(try record.model()), deletedAt: record.deletedAt.map(exact))
        }
        return CabinetContents(locations: locations, boxes: boxes, namedProducts: products)
    }

    /// The database keeps dates to the millisecond, but reading the text back through a calendar can land a hair
    /// off the exact millisecond value. The snapshot reports the exact one, the same `Date` an archive decodes to, so
    /// a cabinet read back equals the cabinet written (F7).
    static func exact(_ date: Date) -> Date {
        Date(timeIntervalSince1970: (date.timeIntervalSince1970 * 1_000).rounded() / 1_000)
    }

    private static func exact(_ location: Location) -> Location {
        Location(
            id: location.id, name: location.name, isDefault: location.isDefault, sortOrder: location.sortOrder,
            createdAt: exact(location.createdAt), updatedAt: exact(location.updatedAt)
        )
    }

    private static func exact(_ product: NamedProduct) -> NamedProduct {
        NamedProduct(
            id: product.id, gtin: product.gtin, name: product.name, category: product.category,
            categoryOverridden: product.categoryOverridden, createdAt: exact(product.createdAt),
            updatedAt: exact(product.updatedAt)
        )
    }

    private static func exact(_ box: Box) -> Box {
        Box(
            id: box.id, product: box.product, category: box.category, categoryOverridden: box.categoryOverridden,
            locationID: box.locationID, expiry: box.expiry, lot: box.lot, serial: box.serial, isOpened: box.isOpened,
            createdAt: exact(box.createdAt), updatedAt: exact(box.updatedAt),
            removal: box.removal.map { Box.Removal(reason: $0.reason, date: exact($0.date)) }
        )
    }

    /// The rules the database enforces, checked before anything is touched so a snapshot it cannot hold names the
    /// broken rule (the same errors as the repositories). The constraints stay the backstop inside the transaction.
    private static func requireHoldable(_ snapshot: CabinetContents) throws {
        let locationIDs = Set(snapshot.locations.map(\.location.id))
        let liveGTINs = snapshot.namedProducts.filter { $0.deletedAt == nil }.map(\.product.gtin)
        guard locationIDs.count == snapshot.locations.count,
              Set(snapshot.boxes.map(\.box.id)).count == snapshot.boxes.count,
              Set(snapshot.namedProducts.map(\.product.id)).count == snapshot.namedProducts.count,
              Set(liveGTINs).count == liveGTINs.count
        else { throw RepositoryError.duplicate }
        guard snapshot.locations.filter(\.location.isDefault).count <= 1 else { throw RepositoryError.duplicateDefault }
        guard snapshot.boxes.allSatisfy({ locationIDs.contains($0.box.locationID) }) else {
            throw RepositoryError.unknownLocation
        }
    }

    private static func repositoryError(for error: DatabaseError) -> any Error {
        switch error.extendedResultCode {
        case .SQLITE_CONSTRAINT_UNIQUE: RepositoryError.duplicateSerial
        case .SQLITE_CONSTRAINT_FOREIGNKEY: RepositoryError.unknownLocation
        case .SQLITE_CONSTRAINT_PRIMARYKEY: RepositoryError.duplicate
        default: error
        }
    }
}
