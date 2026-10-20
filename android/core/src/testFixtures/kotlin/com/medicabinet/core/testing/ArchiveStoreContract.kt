package com.medicabinet.core.testing

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.archive.ArchivedBox
import com.medicabinet.core.archive.ArchivedLocation
import com.medicabinet.core.archive.ArchivedNamedProduct
import com.medicabinet.core.archive.CabinetArchiveStore
import com.medicabinet.core.archive.CabinetContents
import java.time.Instant
import java.util.UUID

/**
 * A store the contract can run on: the port, plus a way to read the named-product backfill marker that `replace`
 * writes (`NamedProductRepository.backfillPending()` on a real store).
 */
interface ArchiveStoreUnderContract : CabinetArchiveStore {
    suspend fun backfillPending(): Boolean
}

/**
 * Behaviour every [CabinetArchiveStore] implementation must satisfy (the fake, plain SQLite in the app) — the Kotlin
 * twin of the Swift `ArchiveStoreContract`; the check names and their order are tied together by
 * `fixtures/archive-store-contract.json`.
 */
object ArchiveStoreContract {
    class Check(val name: String, private val body: suspend (ArchiveStoreUnderContract) -> Unit) {
        /** [make] makes a new, empty store for this check. */
        suspend fun run(make: suspend () -> ArchiveStoreUnderContract) = body(make())

        override fun toString(): String = name
    }

    private class HookFailure : Exception("hook failure")

    val checks: List<Check> = listOf(
        Check("a new store snapshots as empty") { store ->
            val snapshot = store.snapshot()
            require(isEmpty(snapshot), "a new store is not empty")
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
        Check("snapshot includes deleted locations") { store ->
            store.replace(Sample.first) { }
            val locations = store.snapshot().locations
            require(locations.any { it.deletedAt != null }, "a deleted location is missing")
            require(locations.any { it.deletedAt == null }, "a location is missing")
        },
        Check("replace hands the hook exactly the cabinet it replaces") { store ->
            store.replace(Sample.first) { }
            var seen: CabinetContents? = null
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
            val bad = CabinetContents(Sample.second.locations, listOf(lost))
            expectRejected(store, RepositoryError.UNKNOWN_LOCATION, bad)
        },
        Check("a duplicate id is rejected and changes nothing") { store ->
            store.replace(Sample.first) { }
            val twin = Sample.second.boxes.first()
            val bad = CabinetContents(Sample.second.locations, listOf(twin, twin))
            expectRejected(store, RepositoryError.DUPLICATE, bad)
        },
        Check("two default locations are rejected and changes nothing") { store ->
            store.replace(Sample.first) { }
            val defaults = Sample.second.locations.map { ArchivedLocation(it.location.copy(isDefault = true)) } +
                Sample.extraDefault
            expectRejected(store, RepositoryError.DUPLICATE_DEFAULT, CabinetContents(defaults, emptyList()))
        },
        Check("replacing with an empty cabinet empties the store") { store ->
            store.replace(Sample.first) { }
            store.replace(CabinetContents(emptyList(), emptyList())) { }
            require(isEmpty(store.snapshot()), "the store was not emptied")
        },
        Check("snapshot includes named products, forgotten ones too") { store ->
            store.replace(Sample.first) { }
            val snapshot = store.snapshot()
            val products = snapshot.namedProducts
            require(products.any { it.deletedAt != null }, "a forgotten named product is missing")
            require(products.any { it.deletedAt == null }, "a live named product is missing")
            require(products.size == Sample.first.namedProducts.size, "a named product is missing")
            require(!snapshot.namedProductsNeedFill, "a snapshot never asks for the fill")
        },
        Check("replace swaps the named products") { store ->
            store.replace(Sample.first) { }
            store.replace(Sample.second) { }
            require(normalized(store.snapshot()).namedProducts == Sample.second.namedProducts, "not replaced")
            store.replace(Sample.second.copy(namedProducts = emptyList())) { }
            require(store.snapshot().namedProducts.isEmpty(), "the named products were not emptied")
        },
        Check("a version 1 replace marks the named products to fill") { store ->
            store.replace(Sample.second.copy(namedProducts = emptyList(), namedProductsNeedFill = true)) { }
            require(store.backfillPending(), "the fill mark was not set")
            val snapshot = store.snapshot()
            require(snapshot.namedProducts.isEmpty() && !snapshot.namedProductsNeedFill, "the snapshot changed")
        },
        Check("a version 2 replace clears the fill mark") { store ->
            store.replace(Sample.second.copy(namedProductsNeedFill = true)) { }
            require(store.backfillPending(), "the fill mark was not set")
            store.replace(Sample.first) { }
            require(!store.backfillPending(), "the fill mark was not cleared")
        },
        Check("a throwing hook leaves the named products and the fill mark unchanged") { store ->
            store.replace(Sample.first.copy(namedProductsNeedFill = true)) { }
            val failed = runCatching { store.replace(Sample.second) { throw HookFailure() } }.exceptionOrNull()
            require(failed is HookFailure, "the hook's error was not rethrown")
            require(normalized(store.snapshot()) == normalized(Sample.first), "a failed replace changed the cabinet")
            require(store.backfillPending(), "a failed replace cleared the fill mark")
            store.replace(Sample.first) { }
            val other = Sample.second.copy(namedProductsNeedFill = true)
            runCatching { store.replace(other) { throw HookFailure() } }
            require(!store.backfillPending(), "a failed replace set the fill mark")
        },
        Check("two live named products with one gtin are rejected and change nothing") { store ->
            store.replace(Sample.first) { }
            val twins = listOf(Sample.product(7, Sample.GTIN_A), Sample.product(8, Sample.GTIN_A))
            val bad = Sample.second.copy(namedProducts = twins, namedProductsNeedFill = true)
            expectRejected(store, RepositoryError.DUPLICATE, bad)
            require(!store.backfillPending(), "a rejected replace set the fill mark")
            // A forgotten and a live product, or several forgotten ones, may share a GTIN.
            val shared = listOf(
                Sample.product(7, Sample.GTIN_A, forgotten = true),
                Sample.product(8, Sample.GTIN_A),
                Sample.product(9, Sample.GTIN_A, forgotten = true)
            )
            store.replace(Sample.second.copy(namedProducts = shared)) { }
            require(store.snapshot().namedProducts.size == 3, "forgotten twins were not kept")
        }
    )

    // MARK: Helpers

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw ContractFailure(message)
    }

    /** Stores return rows in their own order; the contract compares contents. */
    private fun normalized(snapshot: CabinetContents) = CabinetContents(
        snapshot.locations.sortedBy { it.location.id.toString().uppercase() },
        snapshot.boxes.sortedBy { it.box.id.toString().uppercase() },
        snapshot.namedProducts.sortedBy { it.product.id.toString().uppercase() }
    )

    private fun isEmpty(snapshot: CabinetContents) =
        snapshot.locations.isEmpty() && snapshot.boxes.isEmpty() && snapshot.namedProducts.isEmpty()

    private suspend fun expectRejected(store: CabinetArchiveStore, expected: RepositoryError, bad: CabinetContents) {
        val error = runCatching { store.replace(bad) { } }.exceptionOrNull()
        require(error is RepositoryException && error.error == expected, "expected $expected but got $error")
        require(normalized(store.snapshot()) == normalized(Sample.first), "a rejected replace changed the cabinet")
    }
}

/** Two small, fixed cabinets with every kind of box. */
private object Sample {
    val UNKNOWN_LOCATION: UUID = UUID.fromString("10000009-0000-4000-8000-000000000009")
    private val stamp: Instant = Instant.ofEpochMilli(1_800_000_000_123L)

    fun location(number: Int, name: String, isDefault: Boolean, deleted: Boolean = false) = ArchivedLocation(
        Location(
            id = UUID.fromString("1000000$number-0000-4000-8000-00000000000$number"),
            name = name,
            isDefault = isDefault,
            sortOrder = number,
            createdAt = stamp,
            updatedAt = stamp
        ),
        if (deleted) stamp else null
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

    const val GTIN_A = "03401234567890"
    const val GTIN_B = "03401234567891"

    fun product(number: Int, gtin: String, forgotten: Boolean = false, category: String = "skin-wound") =
        ArchivedNamedProduct(
            NamedProduct(
                id = UUID.fromString("C%07d-0000-4000-8000-%012d".format(number, number)),
                gtin = gtin,
                name = "Product $number",
                category = CategoryID(category),
                categoryOverridden = category != "uncategorized",
                createdAt = stamp,
                updatedAt = stamp.plusSeconds(1)
            ),
            if (forgotten) stamp.plusSeconds(2) else null
        )

    val extraDefault = location(5, "Extra", true)

    val first: CabinetContents = run {
        val home = location(1, "Home", true)
        val car = location(2, "Car", false)
        val old = location(4, "Old cupboard", false, deleted = true)
        val removed = box(2, old.location.id).copy(removal = Box.Removal(RemovalReason.THROWN_AWAY, stamp))
        CabinetContents(
            listOf(home, car, old),
            listOf(
                ArchivedBox(box(1, home.location.id)),
                ArchivedBox(removed),
                ArchivedBox(box(3, car.location.id), stamp)
            ),
            listOf(
                product(1, GTIN_A, forgotten = true),
                product(2, GTIN_A),
                product(3, GTIN_B, category = "uncategorized")
            )
        )
    }

    val second: CabinetContents = run {
        val kitchen = location(3, "Kitchen", true)
        CabinetContents(
            listOf(kitchen),
            listOf(ArchivedBox(box(4, kitchen.location.id))),
            listOf(product(4, GTIN_B))
        )
    }
}
