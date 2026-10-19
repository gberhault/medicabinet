package com.medicabinet.app.persistence

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
    private val database: AppDatabase,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : BoxRepository {
    private val queries get() = database.boxQueries

    override suspend fun activeBoxes(): List<Box> = withContext(io) {
        queries.selectActive().executeAsList().map { it.toModel() }
    }

    override suspend fun box(id: UUID): Box? = withContext(io) {
        queries.selectById(id.stored()).executeAsOneOrNull()?.toModel()
    }

    override suspend fun insert(box: Box): Unit = withContext(io) {
        database.transaction {
            if (queries.selectById(box.id.stored()).executeAsOneOrNull() != null) {
                throw RepositoryException(RepositoryError.DUPLICATE)
            }
            requireLocation(box)
            queries.insert(
                id = box.id.stored(),
                gtin = box.product.gtin,
                productName = box.product.name,
                category = box.category.rawValue,
                categoryOverridden = box.categoryOverridden.stored(),
                locationId = box.locationId.stored(),
                expiryYear = box.expiry?.year?.toLong(),
                expiryMonth = box.expiry?.month?.toLong(),
                expiryDay = box.expiry?.day?.toLong(),
                expirySortKey = box.expiry?.sortKey,
                lot = box.lot,
                serial = box.serial,
                isOpened = box.isOpened.stored(),
                removalReason = box.removal?.reason?.rawValue,
                removedAt = box.removal?.date?.let(StoredDate::write),
                createdAt = StoredDate.write(box.createdAt),
                updatedAt = StoredDate.write(box.updatedAt)
            )
        }
    }

    override suspend fun update(box: Box) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            database.transaction {
                queries.selectById(box.id.stored()).executeAsOneOrNull()
                    ?: throw RepositoryException(RepositoryError.NOT_FOUND)
                requireLocation(box)
                queries.update(
                    gtin = box.product.gtin,
                    productName = box.product.name,
                    category = box.category.rawValue,
                    categoryOverridden = box.categoryOverridden.stored(),
                    locationId = box.locationId.stored(),
                    expiryYear = box.expiry?.year?.toLong(),
                    expiryMonth = box.expiry?.month?.toLong(),
                    expiryDay = box.expiry?.day?.toLong(),
                    expirySortKey = box.expiry?.sortKey,
                    lot = box.lot,
                    serial = box.serial,
                    isOpened = box.isOpened.stored(),
                    updatedAt = now,
                    id = box.id.stored()
                )
            }
        }
    }

    override suspend fun markRemoved(id: UUID, reason: RemovalReason, at: Instant) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            database.transaction {
                requireBox(id)
                queries.markRemoved(reason.rawValue, StoredDate.write(at), now, id.stored())
            }
        }
    }

    override suspend fun undoRemoval(id: UUID) {
        val now = StoredDate.write(clock.now())
        withContext(io) {
            database.transaction {
                requireBox(id)
                queries.undoRemoval(now, id.stored())
            }
        }
    }

    private fun requireBox(id: UUID) {
        queries.selectById(id.stored()).executeAsOneOrNull() ?: throw RepositoryException(RepositoryError.NOT_FOUND)
    }

    private fun requireLocation(box: Box) {
        if (database.locationQueries.selectById(box.locationId.stored()).executeAsOneOrNull() == null) {
            throw RepositoryException(RepositoryError.UNKNOWN_LOCATION)
        }
    }
}
