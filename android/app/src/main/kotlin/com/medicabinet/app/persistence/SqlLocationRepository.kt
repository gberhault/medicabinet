package com.medicabinet.app.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import com.medicabinet.core.Clock
import com.medicabinet.core.Location
import com.medicabinet.core.LocationEditing
import com.medicabinet.core.LocationRepository
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

/** Locations that are *deleted* (`deletedAt`) are invisible to every query here, except `undoDeleteLocation`. */
class SqlLocationRepository(
    private val cabinet: CabinetDatabase,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : LocationRepository,
    LocationEditing by SqlLocationEditing(cabinet, clock, io) {
    override suspend fun all(): List<Location> = withContext(io) { liveLocations() }

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

    override suspend fun cabinetBoxCount(id: UUID): Int = withContext(io) {
        cabinet.database.query(
            "SELECT COUNT(*) FROM box WHERE locationId = ? AND removedAt IS NULL AND deletedAt IS NULL",
            arrayOf(id.stored())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    override fun observeAll(): Flow<List<Location>> = cabinet.changes.map { liveLocations() }.flowOn(io).conflate()

    private fun liveLocations(): List<Location> = cabinet.database.query(
        "SELECT ${Columns.LOCATION} FROM location WHERE deletedAt IS NULL ORDER BY sortOrder, name"
    ).mapRows { toLocation() }

    private fun defaultIn(db: SupportSQLiteDatabase): Location? = db.query(
        "SELECT ${Columns.LOCATION} FROM location WHERE isDefault = 1 AND deletedAt IS NULL LIMIT 1"
    ).mapRows { toLocation() }.firstOrNull()

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
