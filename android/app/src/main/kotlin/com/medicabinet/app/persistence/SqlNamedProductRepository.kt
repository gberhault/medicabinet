package com.medicabinet.app.persistence

import android.database.sqlite.SQLiteConstraintException
import com.medicabinet.core.CategoryID
import com.medicabinet.core.Clock
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.NamedProductSummary
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The products the household named. Each write is one transaction, so a failure leaves the products and the boxes as
 * they were. A *forgotten* product keeps its row (`deletedAt`) and is invisible to every query here, except
 * `undoForget`. Names go in as bound parameters, never into the SQL text.
 */
class SqlNamedProductRepository(
    private val cabinet: CabinetDatabase,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : NamedProductRepository {
    override suspend fun namedProduct(gtin: String): NamedProduct? = withContext(io) {
        cabinet.database.query(
            "SELECT ${Columns.NAMED_PRODUCT} FROM namedProduct WHERE gtin = ? AND deletedAt IS NULL",
            arrayOf(gtin)
        ).mapRows { toNamedProduct() }.firstOrNull()
    }

    override suspend fun remember(gtin: String, name: String, category: CategoryID, categoryOverridden: Boolean) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            // An equal remember writes nothing, so it announces no change either.
            val unchanged = cabinet.read {
                NamedProductSql.live(it, gtin)?.holds(name, category, categoryOverridden) ==
                    true
            }
            if (!unchanged) {
                cabinet.transaction { db ->
                    val stored = NamedProductSql.live(db, gtin)
                    if (stored == null) {
                        NamedProductSql.insert(db, gtin, name, category, categoryOverridden, now)
                    } else {
                        NamedProductSql.update(db, stored.id, name, category, categoryOverridden, now)
                    }
                }
            }
        }
    }

    override fun observeSummaries(): Flow<List<NamedProductSummary>> =
        cabinet.changes.map { NamedProductSql.summaries(cabinet.database) }.flowOn(io).conflate()

    override suspend fun rename(id: UUID, name: String) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                val gtin = NamedProductSql.requireLiveGtin(db, id)
                db.execSQL(
                    "UPDATE namedProduct SET name = ?, updatedAt = ? WHERE id = ?",
                    arrayOf(name, now, id.stored())
                )
                db.execSQL(
                    "UPDATE box SET productName = ?, updatedAt = ? WHERE gtin = ? AND deletedAt IS NULL",
                    arrayOf(name, now, gtin)
                )
            }
        }
    }

    override suspend fun setCategory(id: UUID, category: CategoryID, overridden: Boolean) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                val gtin = NamedProductSql.requireLiveGtin(db, id)
                db.execSQL(
                    "UPDATE namedProduct SET category = ?, categoryOverridden = ?, updatedAt = ? WHERE id = ?",
                    arrayOf(category.rawValue, overridden.stored(), now, id.stored())
                )
                db.execSQL(
                    "UPDATE box SET category = ?, categoryOverridden = ?, updatedAt = ? " +
                        "WHERE gtin = ? AND deletedAt IS NULL",
                    arrayOf(category.rawValue, overridden.stored(), now, gtin)
                )
            }
        }
    }

    override suspend fun forget(id: UUID) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                NamedProductSql.requireLiveGtin(db, id)
                db.execSQL(
                    "UPDATE namedProduct SET deletedAt = ?, updatedAt = ? WHERE id = ?",
                    arrayOf(now, now, id.stored())
                )
            }
        }
    }

    override suspend fun undoForget(id: UUID) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                NamedProductSql.requireRestorable(db, id)
                try {
                    db.execSQL(
                        "UPDATE namedProduct SET deletedAt = NULL, updatedAt = ? WHERE id = ?",
                        arrayOf(now, id.stored())
                    )
                } catch (error: SQLiteConstraintException) {
                    // The partial unique index is the backstop of the check above.
                    throw RepositoryException(RepositoryError.DUPLICATE, error)
                }
            }
        }
    }

    override suspend fun backfillPending(): Boolean = withContext(io) { NamedProductSql.pending(cabinet.database) }

    override suspend fun completeBackfill(products: List<NamedProduct>) {
        withContext(io) {
            cabinet.transaction { db ->
                for (product in products) {
                    // What a scan named between the read of the boxes and this write wins: skip a code already named.
                    db.execSQL(
                        "INSERT INTO namedProduct (${Columns.NAMED_PRODUCT}) SELECT ?, ?, ?, ?, ?, ?, ? " +
                            "WHERE NOT EXISTS (SELECT 1 FROM namedProduct WHERE gtin = ? AND deletedAt IS NULL)",
                        arrayOf(
                            product.id.stored(),
                            product.gtin,
                            product.name,
                            product.category.rawValue,
                            product.categoryOverridden.stored(),
                            StoredDate.write(product.createdAt),
                            StoredDate.write(product.updatedAt),
                            product.gtin
                        )
                    )
                }
                NamedProductSql.clearBackfillMarker(db)
            }
        }
    }
}

private fun NamedProduct.holds(name: String, category: CategoryID, categoryOverridden: Boolean): Boolean =
    this.name == name && this.category == category && this.categoryOverridden == categoryOverridden
