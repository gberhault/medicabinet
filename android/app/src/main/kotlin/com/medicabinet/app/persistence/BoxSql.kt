package com.medicabinet.app.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import com.medicabinet.core.Box
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import java.util.UUID

/** The guards `SqlBoxRepository` runs inside its transactions. A box is *live* while `deletedAt IS NULL`. */
internal object BoxSql {
    fun exists(db: SupportSQLiteDatabase, id: UUID): Boolean =
        db.query("SELECT 1 FROM box WHERE id = ?", arrayOf(id.stored())).use { it.moveToFirst() }

    fun requireLive(db: SupportSQLiteDatabase, id: UUID) {
        val live = db.query("SELECT 1 FROM box WHERE id = ? AND deletedAt IS NULL", arrayOf(id.stored())).use {
            it.moveToFirst()
        }
        if (!live) throw RepositoryException(RepositoryError.NOT_FOUND)
    }

    fun requireLocation(db: SupportSQLiteDatabase, box: Box) {
        val found = db.query("SELECT 1 FROM location WHERE id = ?", arrayOf(box.locationId.stored())).use {
            it.moveToFirst()
        }
        if (!found) throw RepositoryException(RepositoryError.UNKNOWN_LOCATION)
    }

    /** Boxes without both a GTIN and a serial never conflict. The unique index is the backstop. */
    fun requireFreeSerial(db: SupportSQLiteDatabase, gtin: String?, serial: String?, id: UUID) {
        if (gtin == null || serial == null) return
        val taken = db.query(
            "SELECT 1 FROM box WHERE gtin = ? AND serial = ? AND deletedAt IS NULL AND id != ?",
            arrayOf(gtin, serial, id.stored())
        ).use { it.moveToFirst() }
        if (taken) throw RepositoryException(RepositoryError.DUPLICATE_SERIAL)
    }

    /** The GTIN and serial of a deleted box, or `null` when the box is not deleted. */
    fun deletedIdentity(db: SupportSQLiteDatabase, id: UUID): Pair<String?, String?>? =
        db.query("SELECT gtin, serial FROM box WHERE id = ? AND deletedAt IS NOT NULL", arrayOf(id.stored())).use {
            if (it.moveToFirst()) it.strOrNull("gtin") to it.strOrNull("serial") else null
        }
}
