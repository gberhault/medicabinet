package com.medicabinet.app.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import java.util.UUID

/**
 * The guards and small queries `SqlLocationEditing` runs inside its transactions.
 * A location is *live* while `deletedAt IS NULL`.
 */
internal object LocationSql {
    fun isLive(db: SupportSQLiteDatabase, id: UUID): Boolean =
        db.query("SELECT 1 FROM location WHERE id = ? AND deletedAt IS NULL", arrayOf(id.stored())).use {
            it.moveToFirst()
        }

    fun requireLive(db: SupportSQLiteDatabase, id: UUID) {
        if (!isLive(db, id)) throw RepositoryException(RepositoryError.NOT_FOUND)
    }

    /** Why [id] cannot be deleted (moving its boxes to [destination]), or `null` when it can. */
    fun deletionError(db: SupportSQLiteDatabase, id: UUID, destination: UUID?): RepositoryError? = when {
        !isLive(db, id) -> RepositoryError.NOT_FOUND
        isDefault(db, id) -> RepositoryError.DEFAULT_LOCATION
        destination != null && (destination == id || !isLive(db, destination)) -> RepositoryError.UNKNOWN_LOCATION
        destination == null && cabinetBoxCount(db, id) > 0 -> RepositoryError.LOCATION_NOT_EMPTY
        else -> null
    }

    private fun isDefault(db: SupportSQLiteDatabase, id: UUID): Boolean =
        db.query("SELECT 1 FROM location WHERE id = ? AND isDefault = 1", arrayOf(id.stored())).use {
            it.moveToFirst()
        }

    private fun cabinetBoxCount(db: SupportSQLiteDatabase, id: UUID): Int = db.query(
        "SELECT COUNT(*) FROM box WHERE locationId = ? AND removedAt IS NULL AND deletedAt IS NULL",
        arrayOf(id.stored())
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** id → sortOrder of the locations that are not deleted. */
    fun liveOrder(db: SupportSQLiteDatabase): Map<String, Int> =
        db.query("SELECT id, sortOrder FROM location WHERE deletedAt IS NULL").use {
            buildMap { while (it.moveToNext()) put(it.getString(0), it.getInt(1)) }
        }

    /** The names of the locations that are not deleted, except [excluding]. */
    fun namesExcluding(db: SupportSQLiteDatabase, excluding: UUID?): List<String> = db.query(
        "SELECT name FROM location WHERE deletedAt IS NULL AND id != ?",
        arrayOf(excluding?.stored().orEmpty())
    ).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }

    /** `MAX(sortOrder)` among the locations that are not deleted, -1 when there are none. */
    fun lastSortOrder(db: SupportSQLiteDatabase): Int = db.query(
        "SELECT MAX(sortOrder) FROM location WHERE deletedAt IS NULL"
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else -1 }

    /** The name of a deleted location, or `null` when [id] is not deleted. */
    fun deletedName(db: SupportSQLiteDatabase, id: UUID): String? = db.query(
        "SELECT name FROM location WHERE id = ? AND deletedAt IS NOT NULL",
        arrayOf(id.stored())
    ).use { if (it.moveToFirst()) it.getString(0) else null }
}
