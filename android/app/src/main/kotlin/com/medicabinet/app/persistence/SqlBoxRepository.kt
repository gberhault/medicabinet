package com.medicabinet.app.persistence

import android.database.sqlite.SQLiteConstraintException
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
            "SELECT ${Columns.BOX} FROM box WHERE removedAt IS NULL AND deletedAt IS NULL ORDER BY createdAt, id"
        ).mapRows {
            toBox()
        }
    }

    override suspend fun box(id: UUID): Box? = withContext(io) {
        cabinet.database.query(
            "SELECT ${Columns.BOX} FROM box WHERE id = ? AND deletedAt IS NULL",
            arrayOf(id.stored())
        ).mapRows { toBox() }.firstOrNull()
    }

    override suspend fun box(gtin: String, serial: String): Box? = withContext(io) {
        cabinet.database.query(
            "SELECT ${Columns.BOX} FROM box WHERE gtin = ? AND serial = ? AND deletedAt IS NULL LIMIT 1",
            arrayOf(gtin, serial)
        ).mapRows { toBox() }.firstOrNull()
    }

    override suspend fun latestBox(gtin: String): Box? = withContext(io) {
        cabinet.database.query(
            "SELECT ${Columns.BOX} FROM box WHERE gtin = ? AND deletedAt IS NULL " +
                "ORDER BY createdAt DESC, id DESC LIMIT 1",
            arrayOf(gtin)
        ).mapRows { toBox() }.firstOrNull()
    }

    override suspend fun insert(box: Box): Unit = withContext(io) {
        cabinet.transaction { db ->
            if (BoxSql.exists(db, box.id)) throw RepositoryException(RepositoryError.DUPLICATE)
            BoxSql.requireLocation(db, box)
            BoxSql.requireFreeSerial(db, box.product.gtin, box.serial, box.id)
            writingSerial {
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
    }

    override suspend fun update(box: Box) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                BoxSql.requireLive(db, box.id)
                BoxSql.requireLocation(db, box)
                BoxSql.requireFreeSerial(db, box.product.gtin, box.serial, box.id)
                writingSerial {
                    db.execSQL(
                        "UPDATE box SET gtin = ?, productName = ?, category = ?, categoryOverridden = ?, " +
                            "locationId = ?, expiryYear = ?, expiryMonth = ?, expiryDay = ?, expirySortKey = ?, " +
                            "lot = ?, serial = ?, isOpened = ?, updatedAt = ? WHERE id = ?",
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
    }

    override suspend fun markRemoved(id: UUID, reason: RemovalReason, at: Instant) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                BoxSql.requireLive(db, id)
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
                BoxSql.requireLive(db, id)
                db.execSQL(
                    "UPDATE box SET removalReason = NULL, removedAt = NULL, updatedAt = ? WHERE id = ?",
                    arrayOf(now, id.stored())
                )
            }
        }
    }

    override suspend fun delete(id: UUID) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                BoxSql.requireLive(db, id)
                db.execSQL("UPDATE box SET deletedAt = ?, updatedAt = ? WHERE id = ?", arrayOf(now, now, id.stored()))
            }
        }
    }

    override suspend fun undoDelete(id: UUID) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                val (gtin, serial) = BoxSql.deletedIdentity(db, id)
                    ?: throw RepositoryException(RepositoryError.NOT_FOUND)
                BoxSql.requireFreeSerial(db, gtin, serial, id)
                writingSerial {
                    db.execSQL("UPDATE box SET deletedAt = NULL, updatedAt = ? WHERE id = ?", arrayOf(now, id.stored()))
                }
            }
        }
    }

    /** The unique index is the backstop of the `requireFreeSerial` check: a race still ends in `duplicateSerial`. */
    private inline fun writingSerial(write: () -> Unit) {
        try {
            write()
        } catch (error: SQLiteConstraintException) {
            // Only the serial index maps to duplicateSerial; a CHECK or foreign key violation stays itself.
            if (error.message.orEmpty().contains("box.serial")) {
                throw RepositoryException(RepositoryError.DUPLICATE_SERIAL, error)
            }
            throw error
        }
    }
}

/** year, month, day, sortKey — NULL when the expiry is unknown (never defaulted); day is NULL for month precision. */
private fun Box.expiryColumns(): Array<Any?> =
    arrayOf(expiry?.year?.toLong(), expiry?.month?.toLong(), expiry?.day?.toLong(), expiry?.sortKey)
