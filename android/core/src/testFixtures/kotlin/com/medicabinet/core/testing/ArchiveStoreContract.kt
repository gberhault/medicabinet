package com.medicabinet.core.testing

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.archive.ArchivedBox
import com.medicabinet.core.archive.CabinetArchiveStore
import com.medicabinet.core.archive.CabinetSnapshot
import java.time.Instant
import java.util.UUID

/**
 * Behaviour every [CabinetArchiveStore] implementation must satisfy (the fake, plain SQLite in the app) — the Kotlin
 * twin of the Swift `ArchiveStoreContract`; the check names and their order are tied together by
 * `fixtures/archive-store-contract.json`.
 */
object ArchiveStoreContract {
    class Check(val name: String, private val body: suspend (CabinetArchiveStore) -> Unit) {
        /** [make] makes a new, empty store for this check. */
        suspend fun run(make: suspend () -> CabinetArchiveStore) = body(make())

        override fun toString(): String = name
    }

    private class HookFailure : Exception("hook failure")

    val checks: List<Check> = listOf(
        Check("a new store snapshots as empty") { store ->
            val snapshot = store.snapshot()
            require(snapshot.locations.isEmpty() && snapshot.boxes.isEmpty(), "a new store is not empty")
        },
        Check("replace swaps the whole cabinet keeping ids, fields and dates") { store ->
            store.replace(Sample.first) { }
            require(normalized(store.snapshot()) == normalized(Sample.first), "the first cabinet changed")
            store.replace(Sample.second) { }
            require(normalized(store.snapshot()) == normalized(Sample.second), "the old cabinet was not replaced")
        },
        Check("snapshot includes removed and deleted boxes") { store ->
            store.replace(Sample.first) { }
            val boxes = store.snapshot().boxes
            require(boxes.any { it.box.removal != null && it.deletedAt == null }, "a removed box is missing")
            require(boxes.any { it.deletedAt != null }, "a deleted box is missing")
            require(boxes.any { it.box.removal == null && it.deletedAt == null }, "a box in the cabinet is missing")
        },
        Check("replace hands the hook exactly the cabinet it replaces") { store ->
            store.replace(Sample.first) { }
            var seen: CabinetSnapshot? = null
            store.replace(Sample.second) { seen = it }
            require(seen?.let(::normalized) == normalized(Sample.first), "the hook saw another cabinet")
        },
        Check("a throwing hook leaves the cabinet unchanged") { store ->
            store.replace(Sample.first) { }
            val failed = runCatching { store.replace(Sample.second) { throw HookFailure() } }.exceptionOrNull()
            require(failed is HookFailure, "the hook's error was not rethrown")
            require(normalized(store.snapshot()) == normalized(Sample.first), "a failed replace changed the cabinet")
        },
        Check("a box in an unknown location is rejected and changes nothing") { store ->
            store.replace(Sample.first) { }
            val lost = ArchivedBox(Sample.box(1, Sample.UNKNOWN_LOCATION))
            val bad = CabinetSnapshot(Sample.second.locations, listOf(lost))
            expectRejected(store, RepositoryError.UNKNOWN_LOCATION, bad)
        },
        Check("a duplicate id is rejected and changes nothing") { store ->
            store.replace(Sample.first) { }
            val twin = Sample.second.boxes.first()
            val bad = CabinetSnapshot(Sample.second.locations, listOf(twin, twin))
            expectRejected(store, RepositoryError.DUPLICATE, bad)
        },
        Check("two default locations are rejected and changes nothing") { store ->
            store.replace(Sample.first) { }
            val defaults = Sample.second.locations.map { it.copy(isDefault = true) } + Sample.extraDefault
            expectRejected(store, RepositoryError.DUPLICATE_DEFAULT, CabinetSnapshot(defaults, emptyList()))
        },
        Check("replacing with an empty cabinet empties the store") { store ->
            store.replace(Sample.first) { }
            store.replace(CabinetSnapshot(emptyList(), emptyList())) { }
            val snapshot = store.snapshot()
            require(snapshot.locations.isEmpty() && snapshot.boxes.isEmpty(), "the store was not emptied")
        }
    )

    // MARK: Helpers

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw ContractFailure(message)
    }

    /** Stores return rows in their own order; the contract compares contents. */
    private fun normalized(snapshot: CabinetSnapshot) = CabinetSnapshot(
        snapshot.locations.sortedBy { it.id.toString().uppercase() },
        snapshot.boxes.sortedBy { it.box.id.toString().uppercase() }
    )

    private suspend fun expectRejected(store: CabinetArchiveStore, expected: RepositoryError, bad: CabinetSnapshot) {
        val error = runCatching { store.replace(bad) { } }.exceptionOrNull()
        require(error is RepositoryException && error.error == expected, "expected $expected but got $error")
        require(normalized(store.snapshot()) == normalized(Sample.first), "a rejected replace changed the cabinet")
    }
}

/** Two small, fixed cabinets with every kind of box. */
private object Sample {
    val UNKNOWN_LOCATION: UUID = UUID.fromString("10000009-0000-4000-8000-000000000009")
    private val stamp: Instant = Instant.ofEpochMilli(1_800_000_000_123L)

    fun location(number: Int, name: String, isDefault: Boolean) = Location(
        id = UUID.fromString("1000000$number-0000-4000-8000-00000000000$number"),
        name = name,
        isDefault = isDefault,
        sortOrder = number,
        createdAt = stamp,
        updatedAt = stamp
    )

    fun box(number: Int, locationId: UUID) = Box(
        id = UUID.fromString("B%07d-0000-4000-8000-%012d".format(number, number)),
        product = ProductRef("03400936404564", "Box $number"),
        category = CategoryID("pain-fever"),
        categoryOverridden = true,
        locationId = locationId,
        expiry = ExpiryDate.of(2027, number, null),
        lot = "LOT$number",
        serial = "S$number",
        isOpened = true,
        createdAt = stamp,
        updatedAt = stamp
    )

    val extraDefault = location(5, "Extra", true)

    val first: CabinetSnapshot = run {
        val home = location(1, "Home", true)
        val car = location(2, "Car", false)
        val removed = box(2, car.id).copy(removal = Box.Removal(RemovalReason.THROWN_AWAY, stamp))
        CabinetSnapshot(
            listOf(home, car),
            listOf(ArchivedBox(box(1, home.id)), ArchivedBox(removed), ArchivedBox(box(3, car.id), stamp))
        )
    }

    val second: CabinetSnapshot = run {
        val kitchen = location(3, "Kitchen", true)
        CabinetSnapshot(listOf(kitchen), listOf(ArchivedBox(box(4, kitchen.id))))
    }
}
