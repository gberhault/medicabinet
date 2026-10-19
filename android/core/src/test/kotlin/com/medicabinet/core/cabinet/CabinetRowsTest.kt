package com.medicabinet.core.cabinet

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.ProductRef
import java.time.Instant
import java.util.UUID
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CabinetRowsTest {
    private val location = UUID.randomUUID()

    @Test
    fun urgencyRanksExpiredFirstAndOkLastWithUnknownBeforeOk() {
        val ranked = listOf(ExpiryStatus.OK, ExpiryStatus.UNKNOWN, ExpiryStatus.EXPIRING_SOON, ExpiryStatus.EXPIRED)
            .sortedBy { ExpiryUrgency.rank(it) }
        assertEquals(
            listOf(ExpiryStatus.EXPIRED, ExpiryStatus.EXPIRING_SOON, ExpiryStatus.UNKNOWN, ExpiryStatus.OK),
            ranked
        )
    }

    @Test
    fun aFilterIsActiveOnlyWhenSomethingNarrowsTheList() {
        assertFalse(CabinetFilter().isActive)
        assertFalse(CabinetFilter(query = "  \t").isActive)
        assertTrue(CabinetFilter(query = "doli").isActive)
        assertTrue(CabinetFilter(locationId = location).isActive)
        assertTrue(CabinetFilter(status = ExpiryStatus.EXPIRED).isActive)
    }

    @Test
    fun theEmptySnapshotHasNothing() {
        assertTrue(CabinetSnapshot.EMPTY.sections.isEmpty())
        assertEquals(0, CabinetSnapshot.EMPTY.boxCount)
    }

    @Test
    fun aBoxWithAnUnknownExpiryIsNeverRankedAsOkEvenAlone() {
        val box = Box(
            product = ProductRef("03400930000014", "Doliprane"),
            locationId = location,
            createdAt = Instant.ofEpochSecond(1)
        )
        val row = CabinetRows.build(listOf(box), CabinetFilter(), emptyList()) { ExpiryStatus.UNKNOWN }
            .sections.single().rows.single()
        assertEquals(ExpiryStatus.UNKNOWN, row.status)
        assertNull(row.earliestExpiry)
    }

    @Test
    fun theStatusRuleIsAskedOncePerBoxThatPassesTheLocationFilter() {
        val other = UUID.randomUUID()
        val boxes = (1..4).map { index ->
            Box(
                product = ProductRef("0340093000001$index", "P$index"),
                locationId = if (index % 2 == 0) location else other,
                createdAt = Instant.ofEpochSecond(index.toLong())
            )
        }
        var calls = 0
        CabinetRows.build(boxes, CabinetFilter(locationId = location), emptyList()) {
            calls++
            ExpiryStatus.OK
        }
        assertEquals(2, calls)
    }

    /** Performance budget of design §8: grouping + filtering + search on 1 000 boxes, off the main thread. */
    @Test
    fun buildingTheListOfAThousandBoxesStaysWithinBudget() {
        val locations = List(LOCATIONS) { UUID.randomUUID() }
        val categories = List(CATEGORIES) { CategoryID("cat-$it") }
        val statuses = listOf(ExpiryStatus.OK, ExpiryStatus.EXPIRING_SOON, ExpiryStatus.EXPIRED, ExpiryStatus.UNKNOWN)
        val boxes = List(BOXES) { index ->
            val product = index % PRODUCTS
            Box(
                product = ProductRef("034009300%05d".format(product), "Produit $product Éffe"),
                category = categories[product % CATEGORIES],
                locationId = locations[index % LOCATIONS],
                expiry = if (index % 4 == 3) null else ExpiryDate.of(2026 + index % 4, 1 + index % 12, null),
                createdAt = Instant.ofEpochSecond(index.toLong())
            )
        }
        val statusById = boxes.withIndex().associate { (index, box) -> box.id to statuses[index % statuses.size] }
        val filter = CabinetFilter(query = "produit effe", locationId = locations[0])

        val timingsMs = List(RUNS) {
            val nanos = measureNanoTime {
                CabinetRows.build(boxes, filter, categories) { statusById.getValue(it.id) }
            }
            nanos / NANOS_PER_MS
        }
        val median = timingsMs.sorted()[RUNS / 2]
        assertTrue(median < BUDGET_MS, "median $median ms")
    }

    private companion object {
        const val BOXES = 1_000
        const val PRODUCTS = 300
        const val CATEGORIES = 8
        const val LOCATIONS = 4
        const val RUNS = 10
        const val BUDGET_MS = 100.0
        const val NANOS_PER_MS = 1_000_000.0
    }
}
