package com.medicabinet.app.persistence.reference

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.medicabinet.core.CategoryID
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceUnavailableReason
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/**
 * The reference database (BDPM names and categories by CIP13), opened read-only. Nothing here writes to it; it holds no
 * household data.
 */
class ReferenceDatabase private constructor(private val database: SQLiteDatabase, val dataDate: ReferenceDataDate) :
    Closeable {
    /** The product of a CIP13 (withdrawn ones included), or `null` when the database does not know it. */
    fun product(cip13: String): ReferenceProduct? =
        database.rawQuery(
            "SELECT name, category, cis, withdrawnSince FROM presentation WHERE cip13 = ?",
            arrayOf(cip13)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                ReferenceProduct(
                    name = cursor.getString(0),
                    category = CategoryID(cursor.getString(1)),
                    cis = if (cursor.isNull(2)) null else cursor.getString(2),
                    isWithdrawn = !cursor.isNull(3)
                )
            } else {
                null
            }
        }

    /**
     * SHA-256 of the canonical content, the same text the build script hashes into the manifest (design §1.2): the
     * schema version, then every meta row sorted by key, then every presentation sorted by CIP13.
     */
    fun contentSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(text: String) = digest.update(text.toByteArray(Charsets.UTF_8))
        add("user_version\t${schemaVersion(database)}\n")
        database.rawQuery("SELECT key, value FROM meta ORDER BY key", null).use { cursor ->
            while (cursor.moveToNext()) add("meta\t${cursor.getString(0)}\t${cursor.getString(1)}\n")
        }
        val sql = "SELECT cip13, name, category, withdrawnSince, cis FROM presentation ORDER BY cip13"
        database.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                val withdrawn = if (cursor.isNull(WITHDRAWN_COLUMN)) "" else cursor.getString(WITHDRAWN_COLUMN)
                val cis = if (cursor.isNull(CIS_COLUMN)) "" else cursor.getString(CIS_COLUMN)
                add("row\t${cursor.getString(0)}\t${cursor.getString(1)}\t${cursor.getString(2)}\t$withdrawn\t$cis\n")
            }
        }
        return digest.digest().toHex()
    }

    override fun close() = database.close()

    companion object {
        /** The reference schema version this app understands (`PRAGMA user_version`). */
        const val SUPPORTED_SCHEMA_VERSION = 2
        private const val WITHDRAWN_COLUMN = 3
        private const val CIS_COLUMN = 4

        /** Opens and validates the file: right schema version, intact, with a valid data date. */
        fun open(file: File): ReferenceDatabase {
            val database = openReadOnly(file)
            try {
                return validated(database)
            } catch (error: ReferenceUnavailableException) {
                database.close()
                throw error
            } catch (error: SQLiteException) {
                database.close()
                throw ReferenceUnavailableException(ReferenceUnavailableReason.UNREADABLE, error)
            }
        }

        private fun openReadOnly(file: File): SQLiteDatabase {
            if (!file.isFile) throw ReferenceUnavailableException(ReferenceUnavailableReason.MISSING)
            return try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (error: SQLiteException) {
                throw ReferenceUnavailableException(ReferenceUnavailableReason.UNREADABLE, error)
            }
        }

        private fun validated(database: SQLiteDatabase): ReferenceDatabase {
            if (schemaVersion(database) != SUPPORTED_SCHEMA_VERSION) {
                throw ReferenceUnavailableException(ReferenceUnavailableReason.UNSUPPORTED_SCHEMA)
            }
            val intact = database.rawQuery("PRAGMA quick_check", null).use {
                it.moveToFirst() && it.getString(0) == "ok"
            }
            val dataDate = database.rawQuery("SELECT value FROM meta WHERE key = 'dataDate'", null).use { cursor ->
                if (cursor.moveToFirst()) ReferenceDataDate.parse(cursor.getString(0)) else null
            }
            if (!intact || dataDate == null) throw ReferenceUnavailableException(ReferenceUnavailableReason.UNREADABLE)
            return ReferenceDatabase(database, dataDate)
        }

        private fun schemaVersion(database: SQLiteDatabase): Int =
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
