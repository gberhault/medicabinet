package com.medicabinet.app.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import com.medicabinet.core.Clock
import com.medicabinet.core.Location
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlLocationRepository(
    private val cabinet: CabinetDatabase,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : LocationRepository {
    override suspend fun all(): List<Location> = withContext(io) {
        cabinet.database.query("SELECT ${Columns.LOCATION} FROM location ORDER BY sortOrder, name").mapRows {
            toLocation()
        }
    }

    override suspend fun defaultLocation(): Location? = withContext(io) { defaultIn(cabinet.database) }

    override suspend fun insert(location: Location): Unit = withContext(io) {
        cabinet.transaction { db ->
            if (exists(db, location)) throw RepositoryException(RepositoryError.DUPLICATE)
            if (location.isDefault &&
                defaultIn(db) != null
            ) {
                throw RepositoryException(RepositoryError.DUPLICATE_DEFAULT)
            }
            write(db, location)
        }
    }

    override suspend fun ensureDefault(name: String): Location {
        val now = clock.now()
        return withContext(io) {
            cabinet.transaction { db ->
                defaultIn(db) ?: Location(name = name, isDefault = true, createdAt = now).also { write(db, it) }
            }
        }
    }

    private fun defaultIn(db: SupportSQLiteDatabase): Location? =
        db.query("SELECT ${Columns.LOCATION} FROM location WHERE isDefault = 1 LIMIT 1").mapRows {
            toLocation()
        }.firstOrNull()

    private fun exists(db: SupportSQLiteDatabase, location: Location): Boolean =
        db.query("SELECT 1 FROM location WHERE id = ?", arrayOf(location.id.stored())).use { it.moveToFirst() }

    private fun write(db: SupportSQLiteDatabase, location: Location) = db.execSQL(
        "INSERT INTO location (${Columns.LOCATION}) VALUES (?, ?, ?, ?, ?, ?)",
        arrayOf(
            location.id.stored(),
            location.name,
            location.isDefault.stored(),
            location.sortOrder.toLong(),
            StoredDate.write(location.createdAt),
            StoredDate.write(location.updatedAt)
        )
    )
}
