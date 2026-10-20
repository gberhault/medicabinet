package com.medicabinet.app.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.NamedProductSummary
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.named.NamedProductOrdering
import java.util.UUID

/**
 * The guards and small queries `SqlNamedProductRepository` runs inside its transactions. A named product is *live*
 * while `deletedAt IS NULL`; a *forgotten* one keeps its row.
 */
internal object NamedProductSql {
    /** The GTIN of the live product [id], or throws `notFound` (unknown or forgotten). */
    fun requireLiveGtin(db: SupportSQLiteDatabase, id: UUID): String =
        db.query("SELECT gtin FROM namedProduct WHERE id = ? AND deletedAt IS NULL", arrayOf(id.stored())).use {
            if (it.moveToFirst()) it.getString(0) else throw RepositoryException(RepositoryError.NOT_FOUND)
        }

    /**
     * Throws `notFound` when [id] is not a forgotten product, and `duplicate` when another live product holds its
     * GTIN (a code named again meanwhile).
     */
    fun requireRestorable(db: SupportSQLiteDatabase, id: UUID) {
        val gtin = db.query(
            "SELECT gtin FROM namedProduct WHERE id = ? AND deletedAt IS NOT NULL",
            arrayOf(id.stored())
        ).use { if (it.moveToFirst()) it.getString(0) else null }
            ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        if (live(db, gtin) != null) throw RepositoryException(RepositoryError.DUPLICATE)
    }

    fun live(db: SupportSQLiteDatabase, gtin: String): NamedProduct? = db.query(
        "SELECT ${Columns.NAMED_PRODUCT} FROM namedProduct WHERE gtin = ? AND deletedAt IS NULL",
        arrayOf(gtin)
    ).mapRows { toNamedProduct() }.firstOrNull()

    fun insert(
        db: SupportSQLiteDatabase,
        gtin: String,
        name: String,
        category: CategoryID,
        categoryOverridden: Boolean,
        now: String
    ) = db.execSQL(
        "INSERT INTO namedProduct (${Columns.NAMED_PRODUCT}) VALUES (?, ?, ?, ?, ?, ?, ?)",
        arrayOf(UUID.randomUUID().stored(), gtin, name, category.rawValue, categoryOverridden.stored(), now, now)
    )

    fun update(
        db: SupportSQLiteDatabase,
        id: UUID,
        name: String,
        category: CategoryID,
        categoryOverridden: Boolean,
        now: String
    ) = db.execSQL(
        "UPDATE namedProduct SET name = ?, category = ?, categoryOverridden = ?, updatedAt = ? WHERE id = ?",
        arrayOf(name, category.rawValue, categoryOverridden.stored(), now, id.stored())
    )

    /** One grouped query: no query per row. Ordered by the core's rule, which SQLite's collation cannot express. */
    fun summaries(db: SupportSQLiteDatabase): List<NamedProductSummary> = db.query(
        "SELECT n.id, n.gtin, n.name, n.category, n.categoryOverridden, n.createdAt, n.updatedAt, " +
            "COALESCE(c.boxes, 0) AS boxes FROM namedProduct AS n LEFT JOIN " +
            "(SELECT gtin, COUNT(*) AS boxes FROM box WHERE removedAt IS NULL AND deletedAt IS NULL " +
            "AND gtin IS NOT NULL GROUP BY gtin) AS c ON c.gtin = n.gtin WHERE n.deletedAt IS NULL"
    ).mapRows { NamedProductSummary(toNamedProduct(), getInt(getColumnIndexOrThrow("boxes"))) }
        .sortedWith(compareBy(NamedProductOrdering.BY_NAME) { it.product })

    fun pending(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT 1 FROM cabinetMeta WHERE key = ?", arrayOf(BACKFILL_KEY)).use { it.moveToFirst() }

    fun clearBackfillMarker(db: SupportSQLiteDatabase) =
        db.execSQL("DELETE FROM cabinetMeta WHERE key = ?", arrayOf(BACKFILL_KEY))

    const val BACKFILL_KEY = "namedProductBackfill"
}
