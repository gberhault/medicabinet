package com.medicabinet.core.testing

import com.medicabinet.core.Box
import com.medicabinet.core.BoxRepository
import com.medicabinet.core.Clock
import com.medicabinet.core.Location
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.cabinet.LocationName
import com.medicabinet.core.cabinet.LocationNameError
import com.medicabinet.core.cabinet.LocationNameException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory fake implementing both ports; also documents the expected behaviour. */
@Suppress("TooManyFunctions") // one function per port operation, location and box ports together
class InMemoryCabinetStore(private val clock: Clock) :
    LocationRepository,
    BoxRepository {
    private val mutex = Mutex()
    private val locations = mutableMapOf<UUID, Location>()
    private val deletedLocations = mutableSetOf<UUID>()
    private val boxes = mutableMapOf<UUID, Box>()
    private val deleted = mutableSetOf<UUID>()

    /** Bumped after every write; the observation flows re-read the store when it changes. */
    private val version = MutableStateFlow(0L)

    // LocationRepository

    override suspend fun all(): List<Location> = mutex.withLock { liveLocations() }

    override suspend fun defaultLocation(): Location? = mutex.withLock { liveLocations().firstOrNull { it.isDefault } }

    override suspend fun insert(location: Location) = mutex.withLock {
        if (locations.containsKey(location.id)) throw RepositoryException(RepositoryError.DUPLICATE)
        if (location.isDefault && liveLocations().any { it.isDefault }) {
            throw RepositoryException(RepositoryError.DUPLICATE_DEFAULT)
        }
        locations[location.id] = location
        changed()
    }

    override suspend fun ensureDefault(name: String): Location = mutex.withLock {
        liveLocations().firstOrNull { it.isDefault }
            ?: Location(name = name, isDefault = true, createdAt = clock.now()).also {
                locations[it.id] = it
                changed()
            }
    }

    override suspend fun create(name: String): Location = mutex.withLock {
        val valid = validName(name, excluding = null)
        val last = liveLocations().maxOfOrNull { it.sortOrder } ?: -1
        Location(name = valid, sortOrder = last + 1, createdAt = clock.now()).also {
            locations[it.id] = it
            changed()
        }
    }

    override suspend fun rename(id: UUID, name: String) = mutex.withLock {
        val stored = liveLocation(id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        locations[id] = stored.copy(name = validName(name, excluding = id), updatedAt = clock.now())
        changed()
    }

    override suspend fun reorder(ids: List<UUID>) = mutex.withLock {
        val live = liveLocations()
        if (ids.size != live.size || ids.toSet() != live.map { it.id }.toSet()) {
            throw RepositoryException(RepositoryError.NOT_FOUND)
        }
        ids.forEachIndexed { index, id ->
            val stored = locations.getValue(id)
            if (stored.sortOrder != index) locations[id] = stored.copy(sortOrder = index, updatedAt = clock.now())
        }
        changed()
    }

    override suspend fun setDefault(id: UUID) = mutex.withLock {
        liveLocation(id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        for (stored in liveLocations().filter { it.isDefault != (it.id == id) }) {
            locations[stored.id] = stored.copy(isDefault = stored.id == id, updatedAt = clock.now())
        }
        changed()
    }

    override suspend fun cabinetBoxCount(id: UUID): Int =
        mutex.withLock { currentActiveBoxes().count { it.locationId == id } }

    override suspend fun deleteLocation(id: UUID, movingBoxesTo: UUID?) = mutex.withLock {
        val stored = liveLocation(id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        if (stored.isDefault) throw RepositoryException(RepositoryError.DEFAULT_LOCATION)
        if (movingBoxesTo != null && (movingBoxesTo == id || liveLocation(movingBoxesTo) == null)) {
            throw RepositoryException(RepositoryError.UNKNOWN_LOCATION)
        }
        val inside = currentActiveBoxes().filter { it.locationId == id }
        if (inside.isNotEmpty() && movingBoxesTo == null) throw RepositoryException(RepositoryError.LOCATION_NOT_EMPTY)
        if (movingBoxesTo != null) {
            for (box in inside) boxes[box.id] = box.copy(locationId = movingBoxesTo, updatedAt = clock.now())
        }
        locations[id] = stored.copy(updatedAt = clock.now())
        deletedLocations += id
        changed()
    }

    override suspend fun undoDeleteLocation(id: UUID) = mutex.withLock {
        val stored = locations[id]?.takeIf { id in deletedLocations }
            ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        validName(stored.name, excluding = id)
        locations[id] = stored.copy(updatedAt = clock.now())
        deletedLocations -= id
        changed()
    }

    override fun observeAll(): Flow<List<Location>> = version.map { mutex.withLock { liveLocations() } }

    // BoxRepository

    override suspend fun activeBoxes(): List<Box> = mutex.withLock { currentActiveBoxes() }

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
        if (liveLocation(box.locationId) == null) throw RepositoryException(RepositoryError.UNKNOWN_LOCATION)
        if (serialTaken(box)) throw RepositoryException(RepositoryError.DUPLICATE_SERIAL)
        boxes[box.id] = box
        changed()
    }

    override suspend fun update(box: Box) = mutex.withLock {
        val stored = liveOrNull(box.id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        if (liveLocation(box.locationId) == null) throw RepositoryException(RepositoryError.UNKNOWN_LOCATION)
        if (serialTaken(box)) throw RepositoryException(RepositoryError.DUPLICATE_SERIAL)
        boxes[box.id] = box.copy(createdAt = stored.createdAt, removal = stored.removal, updatedAt = clock.now())
        changed()
    }

    override suspend fun markRemoved(id: UUID, reason: RemovalReason, at: Instant) = mutex.withLock {
        val stored = liveOrNull(id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        boxes[id] = stored.copy(removal = Box.Removal(reason, at), updatedAt = clock.now())
        changed()
    }

    override suspend fun undoRemoval(id: UUID) = mutex.withLock {
        val stored = liveOrNull(id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        boxes[id] = stored.copy(removal = null, updatedAt = clock.now())
        changed()
    }

    override suspend fun delete(id: UUID) = mutex.withLock {
        val stored = liveOrNull(id) ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        boxes[id] = stored.copy(updatedAt = clock.now())
        deleted += id
        changed()
    }

    override suspend fun undoDelete(id: UUID) = mutex.withLock {
        val stored = boxes[id]?.takeIf { id in deleted } ?: throw RepositoryException(RepositoryError.NOT_FOUND)
        if (serialTaken(stored)) throw RepositoryException(RepositoryError.DUPLICATE_SERIAL)
        boxes[id] = stored.copy(updatedAt = clock.now())
        deleted -= id
        changed()
    }

    override fun observeCabinet(): Flow<List<Box>> = version.map { mutex.withLock { currentActiveBoxes() } }

    // Private

    private fun changed() = version.update { it + 1 }

    private fun liveLocation(id: UUID): Location? = locations[id]?.takeIf { id !in deletedLocations }

    /** Ordered by `sortOrder`, then name. */
    private fun liveLocations(): List<Location> =
        locations.values.filter { it.id !in deletedLocations }.sortedWith(compareBy({ it.sortOrder }, { it.name }))

    private fun live(): List<Box> = boxes.values.filter { it.id !in deleted }

    private fun liveOrNull(id: UUID): Box? = boxes[id]?.takeIf { id !in deleted }

    private fun currentActiveBoxes(): List<Box> = live().filter { it.isInCabinet }.sortedBy { it.createdAt }

    /** Trims and validates [raw] against the other locations that are not deleted. */
    private fun validName(raw: String, excluding: UUID?): String {
        val others = liveLocations().filter { it.id != excluding }.map { it.name }
        try {
            return LocationName.validate(raw, others)
        } catch (error: LocationNameException) {
            if (error.error == LocationNameError.DUPLICATE_NAME) {
                throw RepositoryException(RepositoryError.DUPLICATE_NAME, error)
            }
            throw error
        }
    }

    /** Another non-deleted box already has this box's GTIN and serial (boxes without both never conflict). */
    private fun serialTaken(box: Box): Boolean {
        val gtin = box.product.gtin
        val serial = box.serial
        return gtin != null &&
            serial != null &&
            live().any { it.id != box.id && it.product.gtin == gtin && it.serial == serial }
    }
}
