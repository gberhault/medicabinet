package com.medicabinet.app.persistence

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.archive.ArchivedBox
import com.medicabinet.core.archive.ArchivedLocation
import com.medicabinet.core.archive.ArchivedNamedProduct
import com.medicabinet.core.archive.CabinetArchiveStore
import com.medicabinet.core.archive.CabinetContents
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The whole cabinet for export and import (design §5.1): every location, box and named product, deleted and forgotten
 * ones included, read in one transaction; replaced in one write transaction with the automatic backup taken inside it
 * (§1.2).
 */
class SqlCabinetArchiveStore(
    private val cabinet: CabinetDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : CabinetArchiveStore {
    override suspend fun snapshot(): CabinetContents = withContext(io) { cabinet.read(::contentsOf) }

    override suspend fun replace(snapshot: CabinetContents, beforeReplacing: (CabinetContents) -> Unit) {
        withContext(io) {
            // Refused before anything happens, so a cabinet the database cannot hold costs no backup file either.
            requireHoldable(snapshot)
            cabinet.transaction { db ->
                beforeReplacing(contentsOf(db))
                // Boxes before locations: location rows are referenced with ON DELETE RESTRICT. Named products point at
                // nothing.
                db.execSQL("DELETE FROM namedProduct")
                db.execSQL("DELETE FROM box")
                db.execSQL("DELETE FROM location")
                ArchiveRowWriter.locations(db, snapshot.locations)
                ArchiveRowWriter.boxes(db, snapshot.boxes)
                ArchiveRowWriter.namedProducts(db, snapshot.namedProducts)
                writeBackfillMarker(db, pending = snapshot.namedProductsNeedFill)
            }
        }
    }

    private fun contentsOf(db: SupportSQLiteDatabase): CabinetContents {
        val locations = db.query(
            "SELECT ${Columns.LOCATION}, deletedAt FROM location ORDER BY sortOrder, id"
        ).mapRows { ArchivedLocation(toLocation(), deletedAt("location")) }
        val boxes = db.query("SELECT ${Columns.BOX}, deletedAt FROM box ORDER BY createdAt, id").mapRows {
            ArchivedBox(toBox(), deletedAt("box"))
        }
        val products = db.query(
            "SELECT ${Columns.NAMED_PRODUCT}, deletedAt FROM namedProduct ORDER BY createdAt, id"
        ).mapRows { ArchivedNamedProduct(toNamedProduct(), deletedAt("namedProduct")) }
        return CabinetContents(locations, boxes, products)
    }

    /**
     * The backfill marker is state of the cabinet database (`cabinetMeta`), so it is replaced in the same transaction
     * as the rows: pending when the archive came from a version 1 file (the products are filled from the boxes after
     * the import), absent otherwise (the file's products are the household's).
     */
    private fun writeBackfillMarker(db: SupportSQLiteDatabase, pending: Boolean) {
        if (pending) NamedProductSql.setBackfillMarker(db) else NamedProductSql.clearBackfillMarker(db)
    }

    private fun Cursor.deletedAt(table: String): Instant? = strOrNull("deletedAt")?.let { StoredDate.read(it, table) }

    /** The database's own rules, checked up front so a refusal is a typed error (the constraints stay the backstop). */
    private fun requireHoldable(snapshot: CabinetContents) {
        val locationIds = snapshot.locations.map { it.location.id }.toSet()
        val liveSerials = snapshot.boxes
            .filter { it.deletedAt == null && it.box.product.gtin != null && it.box.serial != null }
            .map { it.box.product.gtin to it.box.serial }
        val liveGtins = snapshot.namedProducts.filter { it.deletedAt == null }.map { it.product.gtin }
        val problem = when {
            locationIds.size != snapshot.locations.size -> RepositoryError.DUPLICATE
            snapshot.boxes.map { it.box.id }.toSet().size != snapshot.boxes.size -> RepositoryError.DUPLICATE
            snapshot.namedProducts.map { it.product.id }.toSet().size != snapshot.namedProducts.size ->
                RepositoryError.DUPLICATE
            liveGtins.toSet().size != liveGtins.size -> RepositoryError.DUPLICATE
            snapshot.locations.count { it.location.isDefault } > 1 -> RepositoryError.DUPLICATE_DEFAULT
            snapshot.boxes.any { it.box.locationId !in locationIds } -> RepositoryError.UNKNOWN_LOCATION
            liveSerials.toSet().size != liveSerials.size -> RepositoryError.DUPLICATE_SERIAL
            else -> null
        }
        if (problem != null) throw RepositoryException(problem)
    }
}
