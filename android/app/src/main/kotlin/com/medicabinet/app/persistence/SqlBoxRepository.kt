package com.medicabinet.app.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import com.medicabinet.core.Box
import com.medicabinet.core.BoxRepository
import com.medicabinet.core.Clock
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlBoxRepository(
    private val cabinet: CabinetDatabase,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : BoxRepository {
    override suspend fun activeBoxes(): List<Box> = withContext(io) {
        cabinet.database.query(
            "SELECT ${Columns.BOX} FROM box WHERE removedAt IS NULL ORDER BY createdAt, id"
        ).mapRows {
            toBox()
        }
    }

    override suspend fun box(id: UUID): Box? = withContext(io) {
        cabinet.database.query("SELECT ${Columns.BOX} FROM box WHERE id = ?", arrayOf(id.stored())).mapRows {
            toBox()
        }.firstOrNull()
    }

    override suspend fun insert(box: Box): Unit = withContext(io) {
        cabinet.transaction { db ->
            if (boxExists(db, box.id)) throw RepositoryException(RepositoryError.DUPLICATE)
            requireLocation(db, box)
            db.execSQL(
                "INSERT INTO box (${Columns.BOX}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    box.id.stored(), box.product.gtin, box.product.name, box.category.rawValue,
                    box.categoryOverridden.stored(), box.locationId.stored(),
                    *box.expiryColumns(),
                    box.lot, box.serial, box.isOpened.stored(),
                    box.removal?.reason?.rawValue, box.removal?.date?.let(StoredDate::write),
                    StoredDate.write(box.createdAt), StoredDate.write(box.updatedAt)
                )
            )
        }
    }

    override suspend fun update(box: Box) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                requireBox(db, box.id)
                requireLocation(db, box)
                db.execSQL(
                    "UPDATE box SET gtin = ?, productName = ?, category = ?, categoryOverridden = ?, locationId = ?, " +
                        "expiryYear = ?, expiryMonth = ?, expiryDay = ?, expirySortKey = ?, lot = ?, serial = ?, " +
                        "isOpened = ?, updatedAt = ? WHERE id = ?",
                    arrayOf(
                        box.product.gtin, box.product.name, box.category.rawValue, box.categoryOverridden.stored(),
                        box.locationId.stored(),
                        *box.expiryColumns(),
                        box.lot, box.serial, box.isOpened.stored(), now, box.id.stored()
                    )
                )
            }
        }
    }

    override suspend fun markRemoved(id: UUID, reason: RemovalReason, at: Instant) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                requireBox(db, id)
                db.execSQL(
                    "UPDATE box SET removalReason = ?, removedAt = ?, updatedAt = ? WHERE id = ?",
                    arrayOf(reason.rawValue, StoredDate.write(at), now, id.stored())
                )
            }
        }
    }

    override suspend fun undoRemoval(id: UUID) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                requireBox(db, id)
                db.execSQL(
                    "UPDATE box SET removalReason = NULL, removedAt = NULL, updatedAt = ? WHERE id = ?",
                    arrayOf(now, id.stored())
                )
            }
        }
    }

    private fun boxExists(db: SupportSQLiteDatabase, id: UUID): Boolean =
        db.query("SELECT 1 FROM box WHERE id = ?", arrayOf(id.stored())).use { it.moveToFirst() }

    private fun requireBox(db: SupportSQLiteDatabase, id: UUID) {
        if (!boxExists(db, id)) throw RepositoryException(RepositoryError.NOT_FOUND)
    }

    private fun requireLocation(db: SupportSQLiteDatabase, box: Box) {
        val found = db.query("SELECT 1 FROM location WHERE id = ?", arrayOf(box.locationId.stored())).use {
            it.moveToFirst()
        }
        if (!found) throw RepositoryException(RepositoryError.UNKNOWN_LOCATION)
    }
}

/** year, month, day, sortKey — NULL when the expiry is unknown (never defaulted); day is NULL for month precision. */
private fun Box.expiryColumns(): Array<Any?> =
    arrayOf(expiry?.year?.toLong(), expiry?.month?.toLong(), expiry?.day?.toLong(), expiry?.sortKey)
