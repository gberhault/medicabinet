package com.medicabinet.app.persistence

import com.medicabinet.core.Clock
import com.medicabinet.core.Location
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlLocationRepository(
    private val database: AppDatabase,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : LocationRepository {
    private val queries get() = database.locationQueries

    override suspend fun all(): List<Location> = withContext(io) {
        queries.selectAll().executeAsList().map { it.toModel() }
    }

    override suspend fun defaultLocation(): Location? = withContext(io) {
        queries.selectDefault().executeAsOneOrNull()?.toModel()
    }

    override suspend fun insert(location: Location): Unit = withContext(io) {
        database.transaction {
            if (queries.selectById(location.id.stored()).executeAsOneOrNull() != null) {
                throw RepositoryException(RepositoryError.DUPLICATE)
            }
            if (location.isDefault && queries.selectDefault().executeAsOneOrNull() != null) {
                throw RepositoryException(RepositoryError.DUPLICATE_DEFAULT)
            }
            write(location)
        }
    }

    override suspend fun ensureDefault(name: String): Location {
        val now = clock.now()
        return withContext(io) {
            database.transactionWithResult {
                queries.selectDefault().executeAsOneOrNull()?.toModel()
                    ?: Location(name = name, isDefault = true, createdAt = now).also { write(it) }
            }
        }
    }

    private fun write(location: Location) = queries.insert(
        id = location.id.stored(),
        name = location.name,
        isDefault = location.isDefault.stored(),
        sortOrder = location.sortOrder.toLong(),
        createdAt = StoredDate.write(location.createdAt),
        updatedAt = StoredDate.write(location.updatedAt)
    )
}
