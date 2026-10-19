package com.medicabinet.core.testing

import com.medicabinet.core.Box
import com.medicabinet.core.BoxRepository
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import java.time.Duration
import java.time.Instant

class ContractFailure(message: String, cause: Throwable? = null) : AssertionError(message, cause)

/**
 * Behaviour every [LocationRepository] + [BoxRepository] implementation must satisfy — the Kotlin twin of
 * the Swift `RepositoryContract`; the check names are tied together by `fixtures/repository-contract.json`.
 * Instants used are whole seconds so stores with millisecond precision round-trip them exactly.
 */
object RepositoryContract {
    class Fixture(val clock: MutableClock, val locations: LocationRepository, val boxes: BoxRepository) {
        fun location(name: String = "Bathroom", isDefault: Boolean = false, sortOrder: Int = 0) =
            Location(name = name, isDefault = isDefault, sortOrder = sortOrder, createdAt = clock.now())

        fun box(location: Location, name: String = "Doliprane", expiry: ExpiryDate? = null) = Box(
            product = ProductRef(gtin = "03400930000001", name = name),
            category = CategoryID("pain-fever"),
            locationId = location.id,
            expiry = expiry,
            lot = "L1",
            serial = "S1",
            createdAt = clock.now()
        )
    }

    class Check(val name: String, private val body: suspend (Fixture) -> Unit) {
        suspend fun run(make: suspend (MutableClock) -> Pair<LocationRepository, BoxRepository>) {
            val clock = MutableClock(EPOCH)
            val (locations, boxes) = make(clock)
            body(Fixture(clock, locations, boxes))
        }

        override fun toString(): String = name
    }

    private val EPOCH: Instant = Instant.ofEpochSecond(1_800_000_000)

    val checks: List<Check> get() = locationChecks + boxChecks

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw ContractFailure(message)
    }

    private suspend fun expect(expected: RepositoryError, work: suspend () -> Unit) {
        try {
            work()
        } catch (thrown: RepositoryException) {
            if (thrown.error == expected) return
            throw ContractFailure("expected $expected but got ${thrown.error}", thrown)
        }
        throw ContractFailure("expected $expected but nothing was thrown")
    }

    private suspend fun expectAnyError(work: suspend () -> Unit) {
        val failure = runCatching { work() }.exceptionOrNull()
        if (failure == null) throw ContractFailure("expected an error but nothing was thrown")
    }

    private val locationChecks: List<Check>
        get() = listOf(
            Check("a new store has no locations and no default") { env ->
                require(env.locations.all().isEmpty(), "all() should be empty")
                require(env.locations.defaultLocation() == null, "defaultLocation() should be null")
            },
            Check("locations are returned by sortOrder then name") { env ->
                env.locations.insert(env.location("B", sortOrder = 1))
                env.locations.insert(env.location("Z", sortOrder = 0))
                env.locations.insert(env.location("A", sortOrder = 1))
                require(env.locations.all().map { it.name } == listOf("Z", "A", "B"), "wrong order")
            },
            Check("ensureDefault creates the default once and is idempotent") { env ->
                val first = env.locations.ensureDefault("Cabinet")
                val second = env.locations.ensureDefault("Other name")
                require(first.isDefault && first.name == "Cabinet", "first should be the default")
                require(second.id == first.id && second.name == "Cabinet", "second call must return the same default")
                require(env.locations.all().size == 1, "only one location expected")
                require(env.locations.defaultLocation()?.id == first.id, "defaultLocation() mismatch")
            },
            Check("ensureDefault creates a default next to existing non-default locations") { env ->
                env.locations.insert(env.location("Kitchen"))
                val created = env.locations.ensureDefault("Cabinet")
                require(created.isDefault, "created location should be the default")
                require(env.locations.all().size == 2, "two locations expected")
            },
            Check("a second default location is rejected") { env ->
                env.locations.insert(env.location("A", isDefault = true))
                expectAnyError { env.locations.insert(env.location("B", isDefault = true)) }
                require(env.locations.all().size == 1, "rejected insert must not persist")
            },
            Check("inserting a known location id is rejected") { env ->
                val location = env.location()
                env.locations.insert(location)
                expectAnyError { env.locations.insert(location) }
            }
        )

    private val boxChecks: List<Check>
        get() = listOf(
            Check("a box round-trips with a day-precise expiry") { env ->
                val location = env.location()
                env.locations.insert(location)
                val box = env.box(location, expiry = ExpiryDate.of(2027, 3, 15))
                env.boxes.insert(box)
                require(env.boxes.box(box.id) == box, "stored box differs from inserted")
            },
            Check("month precision and unknown expiry round-trip distinctly") { env ->
                val location = env.location()
                env.locations.insert(location)
                val monthly = env.box(location, name = "M", expiry = ExpiryDate.of(2028, 2, null))
                val unknown = env.box(location, name = "U", expiry = null)
                env.boxes.insert(monthly)
                env.boxes.insert(unknown)
                require(env.boxes.box(monthly.id)?.expiry?.isMonthPrecision == true, "month precision lost")
                require(env.boxes.box(unknown.id)?.expiry == null, "unknown expiry must stay null")
            },
            Check("markRemoved hides a box from activeBoxes but keeps the record") { env ->
                val location = env.location()
                env.locations.insert(location)
                val box = env.box(location)
                env.boxes.insert(box)
                env.clock.advance(Duration.ofSeconds(60))
                val removedAt = env.clock.now()
                env.boxes.markRemoved(box.id, RemovalReason.USED_UP, removedAt)
                require(env.boxes.activeBoxes().isEmpty(), "removed box must not be active")
                val stored = env.boxes.box(box.id)
                require(stored?.removal == Box.Removal(RemovalReason.USED_UP, removedAt), "removal not recorded")
                require(stored?.updatedAt == removedAt, "updatedAt must come from the clock")
            },
            Check("undoRemoval restores a removed box") { env ->
                val location = env.location()
                env.locations.insert(location)
                val box = env.box(location)
                env.boxes.insert(box)
                env.boxes.markRemoved(box.id, RemovalReason.THROWN_AWAY, env.clock.now())
                env.clock.advance(Duration.ofSeconds(30))
                env.boxes.undoRemoval(box.id)
                require(env.boxes.activeBoxes().map { it.id } == listOf(box.id), "box should be active again")
                require(env.boxes.box(box.id)?.removal == null, "removal should be cleared")
                require(env.boxes.box(box.id)?.updatedAt == env.clock.now(), "updatedAt must be bumped")
            },
            Check("update changes fields, bumps updatedAt, keeps createdAt and removal") { env ->
                val location = env.location()
                env.locations.insert(location)
                val box = env.box(location)
                env.boxes.insert(box)
                env.clock.advance(Duration.ofSeconds(120))
                val edited = box.copy(
                    isOpened = true,
                    category = CategoryID("cold-ent"),
                    categoryOverridden = true,
                    expiry = ExpiryDate.of(2029, 1, 31)
                )
                env.boxes.update(edited)
                val stored = env.boxes.box(box.id)
                require(stored?.isOpened == true && stored.categoryOverridden, "fields not updated")
                require(
                    stored?.category?.rawValue == "cold-ent" && stored.expiry == edited.expiry,
                    "fields not updated"
                )
                require(stored?.updatedAt == env.clock.now(), "updatedAt must be bumped")
                require(stored?.createdAt == box.createdAt, "createdAt must be preserved")
            },
            Check("operations on an unknown box throw notFound") { env ->
                val location = env.location()
                env.locations.insert(location)
                val ghost = env.box(location)
                expect(RepositoryError.NOT_FOUND) { env.boxes.update(ghost) }
                expect(RepositoryError.NOT_FOUND) {
                    env.boxes.markRemoved(ghost.id, RemovalReason.USED_UP, env.clock.now())
                }
                expect(RepositoryError.NOT_FOUND) { env.boxes.undoRemoval(ghost.id) }
                require(env.boxes.box(ghost.id) == null, "unknown id must return null")
            },
            Check("a box in an unknown location or with a known id is rejected") { env ->
                val location = env.location()
                env.locations.insert(location)
                val orphan = env.box(env.location("Ghost"))
                expect(RepositoryError.UNKNOWN_LOCATION) { env.boxes.insert(orphan) }
                val box = env.box(location)
                env.boxes.insert(box)
                expect(RepositoryError.DUPLICATE) { env.boxes.insert(box) }
                require(env.boxes.activeBoxes().size == 1, "rejected inserts must not persist")
            }
        )
}
