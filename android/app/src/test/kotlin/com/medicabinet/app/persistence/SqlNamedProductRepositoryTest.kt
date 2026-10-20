package com.medicabinet.app.persistence

import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.ProductRef
import com.medicabinet.core.testing.MutableClock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Storage details of `SqlNamedProductRepository` that the shared contract does not see. */
@RunWith(RobolectricTestRunner::class)
class SqlNamedProductRepositoryTest {
    private val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
    private val cabinet =
        CabinetDatabase(ApplicationProvider.getApplicationContext(), name = "named-${UUID.randomUUID()}.db")
    private val named = SqlNamedProductRepository(cabinet, clock)
    private val gtin = "03400930000001"

    @After
    fun close() = cabinet.close()

    private fun count(sql: String) = SqlProbe.long(cabinet.database, sql)

    @Test
    fun anAwkwardNameIsStoredAsTypedBecauseItIsABoundParameter() = runBlocking {
        val awkward = "O'Brien\"; DROP TABLE namedProduct; --"
        named.remember(gtin, awkward, CategoryID.UNCATEGORIZED, categoryOverridden = false)
        assertEquals(awkward, named.namedProduct(gtin)?.name)
        assertEquals(1L, count("SELECT COUNT(*) FROM namedProduct"))
    }

    @Test
    fun rememberingEqualValuesAnnouncesNoChange() = runBlocking {
        named.remember(gtin, "Arnica", CategoryID.UNCATEGORIZED, categoryOverridden = false)
        val before = cabinet.changes.value
        named.remember(gtin, "Arnica", CategoryID.UNCATEGORIZED, categoryOverridden = false)
        assertEquals(before, cabinet.changes.value)
        named.remember(gtin, "Arnica 2", CategoryID.UNCATEGORIZED, categoryOverridden = false)
        assertEquals(before + 1, cabinet.changes.value)
    }

    @Test
    fun aRenameThatTheDatabaseRefusesLeavesTheProductAndItsBoxesAsTheyWere() = runBlocking {
        val location = SqlLocationRepository(cabinet, clock).ensureDefault("Cabinet")
        val boxes = SqlBoxRepository(cabinet, clock)
        boxes.insert(
            Box(product = ProductRef(gtin, "Arnica"), locationId = location.id, createdAt = clock.now())
        )
        named.remember(gtin, "Arnica", CategoryID.UNCATEGORIZED, categoryOverridden = false)
        val product = checkNotNull(named.namedProduct(gtin))
        assertFailsWith<SQLiteException> { named.rename(product.id, "x".repeat(201)) }
        assertEquals("Arnica", named.namedProduct(gtin)?.name)
        assertEquals("Arnica", boxes.activeBoxes().single().product.name)
    }

    @Test
    fun completeBackfillIsOneTransactionSoAFailureKeepsTheMarker() = runBlocking {
        val good = NamedProduct(gtin = gtin, name = "Arnica", createdAt = clock.now())
        val bad = NamedProduct(gtin = "03400930000002", name = "", createdAt = clock.now())
        assertFailsWith<SQLiteException> { named.completeBackfill(listOf(good, bad)) }
        assertTrue(named.backfillPending())
        assertEquals(0L, count("SELECT COUNT(*) FROM namedProduct"))
    }

    @Test
    fun theMarkerIsARowOfCabinetMetaThatCompleteBackfillDeletes() = runBlocking {
        assertEquals("pending", SqlProbe.string(cabinet.database, "SELECT value FROM cabinetMeta"))
        named.completeBackfill(emptyList())
        assertFalse(named.backfillPending())
        assertEquals(0L, count("SELECT COUNT(*) FROM cabinetMeta"))
    }

    @Test
    fun aThousandNamedProductsAndBoxesAreListedInOneQueryWithinTheBudget() = runBlocking {
        val location = SqlLocationRepository(cabinet, clock).ensureDefault("Cabinet")
        val stamp = StoredDate.write(clock.now())
        cabinet.transaction { db ->
            repeat(PRODUCTS) { index ->
                val code = "0340093" + index.toString().padStart(7, '0')
                db.execSQL(
                    "INSERT INTO namedProduct (id, gtin, name, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?)",
                    arrayOf(UUID.randomUUID().stored(), code, "Product ${PRODUCTS - index}", stamp, stamp)
                )
                db.execSQL(
                    "INSERT INTO box (id, gtin, productName, locationId, serial, createdAt, updatedAt) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(UUID.randomUUID().stored(), code, "Product", location.id.stored(), "S$index", stamp, stamp)
                )
            }
        }
        val millis = (0 until RUNS).map {
            val started = System.nanoTime()
            val rows = named.observeSummaries().first()
            assertEquals(PRODUCTS, rows.size)
            (System.nanoTime() - started) / NANOS_PER_MILLI
        }.sorted()
        val median = millis[millis.size / 2]
        // Several times the measured cost on a laptop: a regression to one query per row would blow through it.
        assertTrue(median < BUDGET_MS, "median $median ms (runs: $millis)")
        assertEquals(1, named.observeSummaries().first().first().boxesInCabinet)
    }

    private companion object {
        const val PRODUCTS = 1_000
        const val RUNS = 7
        const val NANOS_PER_MILLI = 1_000_000
        const val BUDGET_MS = 100
    }
}
