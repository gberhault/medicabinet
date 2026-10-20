package com.medicabinet.app.persistence

import com.medicabinet.core.Clock
import com.medicabinet.core.Location
import com.medicabinet.core.LocationEditing
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.cabinet.LocationName
import com.medicabinet.core.cabinet.LocationNameError
import com.medicabinet.core.cabinet.LocationNameException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What the user does with the list of locations. Each operation is one transaction, so a failure leaves every
 * location and box as it was. A *deleted* location keeps its row (`deletedAt`): removed and deleted boxes still point
 * at it.
 */
internal class SqlLocationEditing(
    private val cabinet: CabinetDatabase,
    private val clock: Clock,
    private val io: CoroutineDispatcher
) : LocationEditing {
    override suspend fun create(name: String): Location {
        val now = clock.now()
        return withContext(io) {
            cabinet.transaction { db ->
                val valid = validName(name, LocationSql.namesExcluding(db, null))
                Location(name = valid, sortOrder = LocationSql.lastSortOrder(db) + 1, createdAt = now).also {
                    db.execSQL(
                        "INSERT INTO location (${Columns.LOCATION}) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            it.id.stored(),
                            it.name,
                            it.isDefault.stored(),
                            it.sortOrder.toLong(),
                            StoredDate.write(it.createdAt),
                            StoredDate.write(it.updatedAt)
                        )
                    )
                }
            }
        }
    }

    override suspend fun rename(id: UUID, name: String) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                LocationSql.requireLive(db, id)
                val valid = validName(name, LocationSql.namesExcluding(db, id))
                db.execSQL(
                    "UPDATE location SET name = ?, updatedAt = ? WHERE id = ?",
                    arrayOf(valid, now, id.stored())
                )
            }
        }
    }

    override suspend fun reorder(ids: List<UUID>) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                val current = LocationSql.liveOrder(db)
                if (ids.size != current.size || ids.map { it.stored() }.toSet() != current.keys) {
                    throw RepositoryException(RepositoryError.NOT_FOUND)
                }
                ids.forEachIndexed { index, id ->
                    if (current[id.stored()] != index) {
                        db.execSQL(
                            "UPDATE location SET sortOrder = ?, updatedAt = ? WHERE id = ?",
                            arrayOf(index.toLong(), now, id.stored())
                        )
                    }
                }
            }
        }
    }

    override suspend fun setDefault(id: UUID) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                LocationSql.requireLive(db, id)
                // Clear the old default first: the unique index allows a single default at any time.
                db.execSQL(
                    "UPDATE location SET isDefault = 0, updatedAt = ? " +
                        "WHERE isDefault = 1 AND id != ? AND deletedAt IS NULL",
                    arrayOf(now, id.stored())
                )
                db.execSQL(
                    "UPDATE location SET isDefault = 1, updatedAt = ? WHERE id = ? AND isDefault = 0",
                    arrayOf(now, id.stored())
                )
            }
        }
    }

    override suspend fun deleteLocation(id: UUID, movingBoxesTo: UUID?) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                LocationSql.deletionError(db, id, movingBoxesTo)?.let { throw RepositoryException(it) }
                if (movingBoxesTo != null) {
                    db.execSQL(
                        "UPDATE box SET locationId = ?, updatedAt = ? " +
                            "WHERE locationId = ? AND removedAt IS NULL AND deletedAt IS NULL",
                        arrayOf(movingBoxesTo.stored(), now, id.stored())
                    )
                }
                db.execSQL(
                    "UPDATE location SET deletedAt = ?, updatedAt = ? WHERE id = ?",
                    arrayOf(now, now, id.stored())
                )
            }
        }
    }

    override suspend fun undoDeleteLocation(id: UUID) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            cabinet.transaction { db ->
                val name = LocationSql.deletedName(db, id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
                validName(name, LocationSql.namesExcluding(db, id))
                db.execSQL(
                    "UPDATE location SET deletedAt = NULL, updatedAt = ? WHERE id = ?",
                    arrayOf(now, id.stored())
                )
            }
        }
    }

    /** Trims and validates [raw] against [others]; a duplicate is a repository error. */
    private fun validName(raw: String, others: List<String>): String = try {
        LocationName.validate(raw, others)
    } catch (error: LocationNameException) {
        if (error.error == LocationNameError.DUPLICATE_NAME) {
            throw RepositoryException(RepositoryError.DUPLICATE_NAME, error)
        }
        throw error
    }
}
