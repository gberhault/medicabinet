package com.medicabinet.app.persistence

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.medicabinet.core.Box
import com.medicabinet.core.Location
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.archive.ArchivedBox
import com.medicabinet.core.archive.ArchivedLocation
import com.medicabinet.core.archive.CabinetArchiveStore
import com.medicabinet.core.archive.CabinetContents
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The whole cabinet for export and import (design §5.1): every location and every box, deleted ones included, read in
 * one transaction; replaced in one write transaction with the automatic backup taken inside it (§1.2).
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
                // Boxes first: location rows are referenced with ON DELETE RESTRICT.
                db.execSQL("DELETE FROM box")
                db.execSQL("DELETE FROM location")
                insertLocations(db, snapshot.locations)
                insertBoxes(db, snapshot.boxes)
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
        return CabinetContents(locations, boxes)
    }

    private fun Cursor.deletedAt(table: String): Instant? = strOrNull("deletedAt")?.let { StoredDate.read(it, table) }

    /** The database's own rules, checked up front so a refusal is a typed error (the constraints stay the backstop). */
    private fun requireHoldable(snapshot: CabinetContents) {
        val locationIds = snapshot.locations.map { it.location.id }.toSet()
        val liveSerials = snapshot.boxes
            .filter { it.deletedAt == null && it.box.product.gtin != null && it.box.serial != null }
            .map { it.box.product.gtin to it.box.serial }
        val problem = when {
            locationIds.size != snapshot.locations.size -> RepositoryError.DUPLICATE
            snapshot.boxes.map { it.box.id }.toSet().size != snapshot.boxes.size -> RepositoryError.DUPLICATE
            snapshot.locations.count { it.location.isDefault } > 1 -> RepositoryError.DUPLICATE_DEFAULT
            snapshot.boxes.any { it.box.locationId !in locationIds } -> RepositoryError.UNKNOWN_LOCATION
            liveSerials.toSet().size != liveSerials.size -> RepositoryError.DUPLICATE_SERIAL
            else -> null
        }
        if (problem != null) throw RepositoryException(problem)
    }

    private fun insertLocations(db: SupportSQLiteDatabase, locations: List<ArchivedLocation>) {
        db.compileStatement("INSERT INTO location (${Columns.LOCATION}, deletedAt) VALUES (?, ?, ?, ?, ?, ?, ?)").use {
            for (archived in locations) it.insert(archived.location, archived.deletedAt)
        }
    }

    private fun insertBoxes(db: SupportSQLiteDatabase, boxes: List<ArchivedBox>) {
        db.compileStatement(
            "INSERT INTO box (${Columns.BOX}, deletedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use {
            for (archived in boxes) it.insert(archived.box, archived.deletedAt)
        }
    }

    private fun SupportSQLiteStatement.insert(location: Location, deletedAt: Instant?) = insertRow(
        location.id.stored(),
        location.name,
        location.isDefault.stored(),
        location.sortOrder.toLong(),
        StoredDate.write(location.createdAt),
        StoredDate.write(location.updatedAt),
        deletedAt?.let(StoredDate::write)
    )

    /** Ids, fields and dates as given; the expiry sort key is derived, so it is computed here (F5). */
    private fun SupportSQLiteStatement.insert(box: Box, deletedAt: Instant?) = insertRow(
        box.id.stored(),
        box.product.gtin,
        box.product.name,
        box.category.rawValue,
        box.categoryOverridden.stored(),
        box.locationId.stored(),
        box.expiry?.year?.toLong(),
        box.expiry?.month?.toLong(),
        box.expiry?.day?.toLong(),
        box.expiry?.sortKey,
        box.lot,
        box.serial,
        box.isOpened.stored(),
        box.removal?.reason?.rawValue,
        box.removal?.date?.let(StoredDate::write),
        StoredDate.write(box.createdAt),
        StoredDate.write(box.updatedAt),
        deletedAt?.let(StoredDate::write)
    )

    /** Binds [values] to the statement's placeholders, in order, and runs it. */
    private fun SupportSQLiteStatement.insertRow(vararg values: Any?) {
        clearBindings()
        values.forEachIndexed { index, value ->
            when (value) {
                null -> bindNull(index + 1)
                is Long -> bindLong(index + 1, value)
                else -> bindString(index + 1, value.toString())
            }
        }
        executeInsert()
    }
}
