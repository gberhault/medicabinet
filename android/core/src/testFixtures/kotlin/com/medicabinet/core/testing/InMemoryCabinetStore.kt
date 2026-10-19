package com.medicabinet.core.testing

import com.medicabinet.core.Box
import com.medicabinet.core.BoxRepository
import com.medicabinet.core.Clock
import com.medicabinet.core.Location
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory fake implementing both ports; also documents the expected behaviour. */
@Suppress("TooManyFunctions") // one function per port operation, location and box ports together
class InMemoryCabinetStore(private val clock: Clock) :
    LocationRepository,
    BoxRepository {
    private val mutex = Mutex()
    private val locations = mutableMapOf<UUID, Location>()
    private val boxes = mutableMapOf<UUID, Box>()
    private val deleted = mutableSetOf<UUID>()

    // LocationRepository

    override suspend fun all(): List<Location> =
        mutex.withLock { locations.values.sortedWith(compareBy({ it.sortOrder }, { it.name })) }

    override suspend fun defaultLocation(): Location? = mutex.withLock { locations.values.firstOrNull { it.isDefault } }

    override suspend fun insert(location: Location) = mutex.withLock {
        if (locations.containsKey(location.id)) throw RepositoryException(RepositoryError.DUPLICATE)
        if (location.isDefault && locations.values.any { it.isDefault }) {
            throw RepositoryException(RepositoryError.DUPLICATE_DEFAULT)
        }
        locations[location.id] = location
    }

    override suspend fun ensureDefault(name: String): Location = mutex.withLock {
        locations.values.firstOrNull { it.isDefault }
            ?: Location(name = name, isDefault = true, createdAt = clock.now()).also { locations[it.id] = it }
    }

    // BoxRepository

    override suspend fun activeBoxes(): List<Box> =
        mutex.withLock { live().filter { it.isInCabinet }.sortedBy { it.createdAt } }

    override suspend fun box(id: UUID): Box? = mutex.withLock { liveOrNull(id) }

    override suspend fun box(gtin: String, serial: String): Box? =
        mutex.withLock { live().firstOrNull { it.product.gtin == gtin && it.serial == serial } }

    override suspend fun latestBox(gtin: String): Box? = mutex.withLock {
        live().filter { it.product.gtin == gtin }.maxWithOrNull(compareBy({ it.createdAt }, { it.id.toString() }))
    }

    override suspend fun cabinetBoxes(gtin: String): List<Box> = mutex.withLock {
        live().filter { it.isInCabinet && it.product.gtin == gtin }
            .sortedWith(compareBy({ it.createdAt }, { it.id.toString() }))
    }

    override suspend fun insert(box: Box) = mutex.withLock {
        if (boxes.containsKey(box.id)) throw RepositoryException(RepositoryError.DUPLICATE)
        if (!locations.containsKey(box.locationId)) throw RepositoryException(RepositoryError.UNKNOWN_LOCATION)
        if (serialTaken(box)) throw RepositoryException(RepositoryError.DUPLICATE_SERIAL)
        boxes[box.id] = box
    }

    override suspend fun update(box: Box) = mutex.withLock {
        val stored = liveOrNull(box.id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        if (!locations.containsKey(box.locationId)) throw RepositoryException(RepositoryError.UNKNOWN_LOCATION)
        if (serialTaken(box)) throw RepositoryException(RepositoryError.DUPLICATE_SERIAL)
        boxes[box.id] = box.copy(createdAt = stored.createdAt, removal = stored.removal, updatedAt = clock.now())
    }

    override suspend fun markRemoved(id: UUID, reason: RemovalReason, at: Instant) = mutex.withLock {
        val stored = liveOrNull(id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        boxes[id] = stored.copy(removal = Box.Removal(reason, at), updatedAt = clock.now())
    }

    override suspend fun undoRemoval(id: UUID) = mutex.withLock {
        val stored = liveOrNull(id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        boxes[id] = stored.copy(removal = null, updatedAt = clock.now())
    }

    override suspend fun delete(id: UUID) = mutex.withLock {
        val stored = liveOrNull(id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        boxes[id] = stored.copy(updatedAt = clock.now())
        deleted += id
    }

    override suspend fun undoDelete(id: UUID) = mutex.withLock {
        val stored = boxes[id]?.takeIf { id in deleted } ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        if (serialTaken(stored)) throw RepositoryException(RepositoryError.DUPLICATE_SERIAL)
        boxes[id] = stored.copy(updatedAt = clock.now())
        deleted -= id
    }

    private fun live(): List<Box> = boxes.values.filter { it.id !in deleted }

    private fun liveOrNull(id: UUID): Box? = boxes[id]?.takeIf { id !in deleted }

    /** Another non-deleted box already has this box's GTIN and serial (boxes without both never conflict). */
    private fun serialTaken(box: Box): Boolean {
        val gtin = box.product.gtin
        val serial = box.serial
        return gtin != null &&
            serial != null &&
            live().any { it.id != box.id && it.product.gtin == gtin && it.serial == serial }
    }
}
