package com.medicabinet.core.testing

import com.medicabinet.core.Box
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.cabinet.LocationNameError
import com.medicabinet.core.cabinet.LocationNameException
import com.medicabinet.core.testing.RepositoryContract.Check
import com.medicabinet.core.testing.RepositoryContract.expect
import com.medicabinet.core.testing.RepositoryContract.require
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val STEP: Duration = Duration.ofSeconds(30)
private const val WAIT_MS = 5_000L
private const val MAX_NAME = 51

/** Latest value of an observation flow, with a bounded wait for a condition. */
internal class Observed<T : Any>(private val latest: MutableStateFlow<T?>) {
    suspend fun until(what: String, condition: (T) -> Boolean) {
        val found = withTimeoutOrNull(WAIT_MS) { latest.first { it != null && condition(it) } }
        if (found == null) throw ContractFailure("timed out waiting for: $what")
    }
}

/** Keeps one collection of [flow] alive while [body] writes, so pushed updates (not fresh reads) are checked. */
internal suspend fun <T : Any> observing(flow: Flow<T>, body: suspend (Observed<T>) -> Unit) = coroutineScope {
    val latest = MutableStateFlow<T?>(null)
    val job: Job = launch { flow.collect { latest.value = it } }
    try {
        body(Observed(latest))
    } finally {
        job.cancel()
    }
}

private suspend fun expectName(expected: LocationNameError, work: suspend () -> Unit) {
    try {
        work()
    } catch (thrown: LocationNameException) {
        if (thrown.error == expected) return
        throw ContractFailure("expected $expected but got ${thrown.error}", thrown)
    }
    throw ContractFailure("expected $expected but nothing was thrown")
}

/** Checks for managing locations: create, rename, reorder, default, tombstone, and the observation flows. */
internal val RepositoryContract.locationManagementChecks: List<Check>
    get() = createAndRenameChecks + orderAndDefaultChecks + deletionChecksForLocations + observationChecks

private val RepositoryContract.createAndRenameChecks: List<Check>
    get() = listOf(
        Check("deleted locations disappear from all and defaultLocation") { env ->
            val home = env.location("Home", isDefault = true)
            val garage = env.location("Garage", sortOrder = 1)
            env.locations.insert(home)
            env.locations.insert(garage)
            env.locations.deleteLocation(garage.id, null)
            require(env.locations.all().map { it.id } == listOf(home.id), "deleted location listed")
            require(env.locations.defaultLocation()?.id == home.id, "default changed")
        },
        Check("create places a location last and rejects a normalized duplicate name") { env ->
            env.locations.insert(env.location("Home", isDefault = true))
            val kitchen = env.locations.create("  Cuisine ")
            require(kitchen.name == "Cuisine" && kitchen.sortOrder == 1 && !kitchen.isDefault, "created wrongly")
            expect(RepositoryError.DUPLICATE_NAME) { env.locations.create("cuísine") }
            expectName(LocationNameError.EMPTY) { env.locations.create("   ") }
            expectName(LocationNameError.TOO_LONG) { env.locations.create("a".repeat(MAX_NAME)) }
            val garage = env.locations.create("Garage")
            require(garage.sortOrder == 2, "second location must be placed after the first")
            require(env.locations.all().size == 3, "rejected creations must not persist")
        },
        Check("rename rejects a duplicate name and allows a case change of its own name") { env ->
            env.locations.insert(env.location("Home", isDefault = true))
            val kitchen = env.locations.create("Cuisine")
            val garage = env.locations.create("Garage")
            env.clock.advance(STEP)
            env.locations.rename(kitchen.id, "CUISINE")
            val renamed = env.locations.all().first { it.id == kitchen.id }
            require(renamed.name == "CUISINE" && renamed.updatedAt == env.clock.now(), "rename not stored")
            require(renamed.createdAt == kitchen.createdAt, "createdAt must be kept")
            expect(RepositoryError.DUPLICATE_NAME) { env.locations.rename(garage.id, "cuisine") }
            expect(RepositoryError.NOT_FOUND) { env.locations.rename(UUID.randomUUID(), "Anything") }
        }
    )

private val RepositoryContract.orderAndDefaultChecks: List<Check>
    get() = listOf(
        Check("reorder sets sortOrder and rejects an incomplete id list") { env ->
            val first = env.location("A", isDefault = true, sortOrder = 0)
            val second = env.location("B", sortOrder = 1)
            val third = env.location("C", sortOrder = 2)
            listOf(first, second, third).forEach { env.locations.insert(it) }
            env.locations.reorder(listOf(third.id, first.id, second.id))
            val reordered = env.locations.all()
            require(reordered.map { it.id } == listOf(third.id, first.id, second.id), "order not applied")
            require(reordered.map { it.sortOrder } == listOf(0, 1, 2), "sortOrder must be the index")
            env.clock.advance(STEP)
            env.locations.reorder(listOf(third.id, second.id, first.id))
            val swapped = env.locations.all()
            require(swapped.first().updatedAt != env.clock.now(), "an unmoved location must not be stamped")
            require(swapped.drop(1).all { it.updatedAt == env.clock.now() }, "moved ones are stamped")
            expect(RepositoryError.NOT_FOUND) { env.locations.reorder(listOf(first.id, second.id)) }
            expect(RepositoryError.NOT_FOUND) { env.locations.reorder(listOf(first.id, second.id, first.id)) }
            require(env.locations.all().map { it.id } == listOf(third.id, second.id, first.id), "order changed")
        },
        Check("setDefault leaves exactly one default") { env ->
            val home = env.location("Home", isDefault = true)
            val garage = env.location("Garage", sortOrder = 1)
            env.locations.insert(home)
            env.locations.insert(garage)
            env.locations.setDefault(garage.id)
            require(env.locations.defaultLocation()?.id == garage.id, "default not moved")
            require(env.locations.all().count { it.isDefault } == 1, "exactly one default")
            env.locations.setDefault(garage.id)
            require(env.locations.all().count { it.isDefault } == 1, "idempotent")
            expect(RepositoryError.NOT_FOUND) { env.locations.setDefault(UUID.randomUUID()) }
        },
        Check("the default location cannot be deleted") { env ->
            val home = env.location("Home", isDefault = true)
            env.locations.insert(home)
            expect(RepositoryError.DEFAULT_LOCATION) { env.locations.deleteLocation(home.id, null) }
            require(env.locations.all().map { it.id } == listOf(home.id), "default must stay")
        }
    )

private val RepositoryContract.deletionChecksForLocations: List<Check>
    get() = listOf(
        Check("deleting a location with cabinet boxes needs a destination") { env ->
            val home = env.location("Home", isDefault = true)
            val garage = env.location("Garage", sortOrder = 1)
            env.locations.insert(home)
            env.locations.insert(garage)
            val inside = env.box(garage)
            val gone = env.box(garage)
            env.boxes.insert(inside)
            env.boxes.insert(gone)
            env.boxes.markRemoved(gone.id, RemovalReason.USED_UP, env.clock.now())
            require(env.locations.cabinetBoxCount(garage.id) == 1, "removed boxes must not count")
            require(env.locations.cabinetBoxCount(UUID.randomUUID()) == 0, "unknown location counts zero")
            expect(RepositoryError.LOCATION_NOT_EMPTY) { env.locations.deleteLocation(garage.id, null) }
            expect(RepositoryError.UNKNOWN_LOCATION) { env.locations.deleteLocation(garage.id, garage.id) }
            expect(RepositoryError.UNKNOWN_LOCATION) { env.locations.deleteLocation(garage.id, UUID.randomUUID()) }
            expect(RepositoryError.NOT_FOUND) { env.locations.deleteLocation(UUID.randomUUID(), null) }
            require(env.locations.all().size == 2, "failed deletions must change nothing")
            require(env.boxes.box(inside.id)?.locationId == garage.id, "box must not move")
        },
        Check("delete moving boxes moves only boxes in the cabinet and tombstones the location") { env ->
            val home = env.location("Home", isDefault = true)
            val garage = env.location("Garage", sortOrder = 1)
            env.locations.insert(home)
            env.locations.insert(garage)
            val inside = env.box(garage)
            val removed = env.box(garage)
            val mistaken = env.box(garage)
            listOf(inside, removed, mistaken).forEach { env.boxes.insert(it) }
            env.boxes.markRemoved(removed.id, RemovalReason.THROWN_AWAY, env.clock.now())
            env.boxes.delete(mistaken.id)
            env.clock.advance(STEP)
            env.locations.deleteLocation(garage.id, home.id)
            require(env.locations.all().map { it.id } == listOf(home.id), "location must be deleted")
            val moved = env.boxes.box(inside.id)
            require(moved?.locationId == home.id && moved.updatedAt == env.clock.now(), "box must move and be stamped")
            require(env.boxes.box(removed.id)?.locationId == garage.id, "removed box keeps its location")
            env.boxes.undoDelete(mistaken.id)
            require(env.boxes.box(mistaken.id)?.locationId == garage.id, "deleted box keeps its location")
        },
        Check("undoDelete restores an empty deleted location unless its name was taken") { env ->
            val home = env.location("Home", isDefault = true)
            env.locations.insert(home)
            val car = env.locations.create("Voiture")
            env.locations.deleteLocation(car.id, null)
            env.locations.undoDeleteLocation(car.id)
            val restored = env.locations.all()
            require(
                restored.map { it.id } == listOf(home.id, car.id) && restored.last().sortOrder == car.sortOrder,
                "not restored in place"
            )
            env.locations.deleteLocation(car.id, null)
            env.locations.create("voiture")
            expect(RepositoryError.DUPLICATE_NAME) { env.locations.undoDeleteLocation(car.id) }
            expect(RepositoryError.NOT_FOUND) { env.locations.undoDeleteLocation(home.id) }
            expect(RepositoryError.NOT_FOUND) { env.locations.undoDeleteLocation(UUID.randomUUID()) }
        },
        Check("boxes cannot be inserted into or moved to a deleted location") { env ->
            val home = env.location("Home", isDefault = true)
            val garage = env.location("Garage", sortOrder = 1)
            env.locations.insert(home)
            env.locations.insert(garage)
            env.locations.deleteLocation(garage.id, null)
            expect(RepositoryError.UNKNOWN_LOCATION) { env.boxes.insert(env.box(garage)) }
            val box = env.box(home)
            env.boxes.insert(box)
            expect(RepositoryError.UNKNOWN_LOCATION) { env.boxes.update(box.copy(locationId = garage.id)) }
        }
    )

private val RepositoryContract.observationChecks: List<Check>
    get() = listOf(
        Check("observeCabinet emits after each write and excludes removed and deleted boxes") { env ->
            val home = env.location("Home", isDefault = true)
            env.locations.insert(home)
            observing<List<Box>>(env.boxes.observeCabinet()) { seen ->
                seen.until("the initial, empty cabinet") { it.isEmpty() }
                val box = env.box(home)
                env.boxes.insert(box)
                seen.until("the inserted box") { list -> list.map { it.id } == listOf(box.id) }
                env.boxes.markRemoved(box.id, RemovalReason.USED_UP, env.clock.now())
                seen.until("the removed box to disappear") { it.isEmpty() }
                env.boxes.undoRemoval(box.id)
                seen.until("the put-back box") { list -> list.map { it.id } == listOf(box.id) }
                env.boxes.delete(box.id)
                seen.until("the deleted box to disappear") { it.isEmpty() }
            }
        },
        Check("observeAll emits after location changes") { env ->
            val home = env.location("Home", isDefault = true)
            env.locations.insert(home)
            observing(env.locations.observeAll()) { seen ->
                seen.until("the initial list") { list -> list.map { it.id } == listOf(home.id) }
                val kitchen = env.locations.create("Cuisine")
                seen.until("the created location") { list -> list.map { it.id } == listOf(home.id, kitchen.id) }
                env.locations.rename(kitchen.id, "Cave")
                seen.until("the new name") { list -> list.lastOrNull()?.name == "Cave" }
                env.locations.deleteLocation(kitchen.id, null)
                seen.until("the deleted location to disappear") { list -> list.map { it.id } == listOf(home.id) }
            }
        }
    )
