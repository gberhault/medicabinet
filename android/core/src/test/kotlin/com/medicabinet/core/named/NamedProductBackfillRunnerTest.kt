package com.medicabinet.core.named

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.Location
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.named.NamedProductBackfillRunner.Outcome
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceDataSource
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import com.medicabinet.core.testing.MutableClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class NamedProductBackfillRunnerTest {
    private val unknown = "03401900000003"
    private val other = "03401900000010"
    private val known = "03400935955838"
    private val knownCip13 = "3400935955838"
    private val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
    private val store = InMemoryCabinetStore(clock)
    private val available = ReferenceStatus.Available(ReferenceDataDate(2026, 10, 18))

    private fun reference(status: ReferenceStatus = available) =
        InMemoryReferenceData(mapOf(knownCip13 to ReferenceProduct("PARACETAMOL")), status)

    private fun runner(source: ReferenceDataSource = reference()) =
        NamedProductBackfillRunner(store.named, store, source, clock)

    private suspend fun insert(location: Location, gtin: String?, name: String, serial: String = name) {
        store.insert(
            Box(product = ProductRef(gtin, name), locationId = location.id, serial = serial, createdAt = clock.now())
        )
        clock.advance(java.time.Duration.ofSeconds(1))
    }

    private fun test(body: suspend (Location) -> Unit) = runBlocking { body(store.ensureDefault("Cabinet")) }

    @Test
    fun pendingAndAvailableFillsTheUnknownCodesAndClearsTheMarker() = test { location ->
        insert(location, unknown, "Arnica")
        insert(location, known, "PARACETAMOL EXEMPLE")
        insert(location, null, "Thermomètre")
        assertEquals(Outcome.DONE, runner().run())
        assertEquals("Arnica", store.named.namedProduct(unknown)?.name)
        assertNull(store.named.namedProduct(known))
        assertEquals(false, store.named.backfillPending())
    }

    @Test
    fun theNewestBoxOfACodeNamesItAndAUserCategoryIsKept() = test { location ->
        insert(location, unknown, "Old name")
        store.insert(
            Box(
                product = ProductRef(unknown, "New name"),
                category = CategoryID.EYES,
                categoryOverridden = true,
                locationId = location.id,
                serial = "N",
                createdAt = clock.now()
            )
        )
        runner().run()
        val product = assertNotNull(store.named.namedProduct(unknown))
        assertEquals("New name", product.name)
        assertEquals(CategoryID.EYES, product.category)
        assertTrue(product.categoryOverridden)
    }

    @Test
    fun anUnavailableReferenceWritesNothingAndTheFillStaysPending() = test { location ->
        insert(location, unknown, "Arnica")
        val unavailable = reference(ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING))
        assertEquals(Outcome.REFERENCE_UNAVAILABLE, runner(unavailable).run())
        assertNull(store.named.namedProduct(unknown))
        assertTrue(store.named.backfillPending())
    }

    @Test
    fun aLookupThatThrowsWritesNothingAndTheFillStaysPending() = test { location ->
        insert(location, unknown, "Arnica")
        insert(location, other, "Collyre")
        val failing = object : ReferenceDataSource {
            override suspend fun status() = available

            override suspend fun product(gtin: String): ReferenceProduct? =
                if (gtin == other) throw RepositoryException(RepositoryError.NOT_FOUND) else null
        }
        assertEquals(Outcome.LOOKUP_FAILED, runner(failing).run())
        assertNull(store.named.namedProduct(unknown))
        assertTrue(store.named.backfillPending())
    }

    @Test
    fun whenTheFillIsNotPendingNoLookupIsMade() = test { location ->
        insert(location, unknown, "Arnica")
        store.named.completeBackfill(emptyList())
        var lookups = 0
        val counting = object : ReferenceDataSource {
            override suspend fun status(): ReferenceStatus {
                lookups++
                return available
            }

            override suspend fun product(gtin: String): ReferenceProduct? {
                lookups++
                return null
            }
        }
        assertEquals(Outcome.NOT_PENDING, runner(counting).run())
        assertEquals(0, lookups)
        assertNull(store.named.namedProduct(unknown))
    }

    @Test
    fun aProductNamedWhileTheFillWasRunningIsKept() = test { location ->
        insert(location, unknown, "From a box")
        insert(location, other, "Collyre")
        // A scan right after the upgrade names a code between the read of the boxes and the write of the fill.
        val scanNamesIt = object : ReferenceDataSource {
            override suspend fun status() = available

            override suspend fun product(gtin: String): ReferenceProduct? {
                store.named.remember(unknown, "Typed meanwhile", CategoryID.UNCATEGORIZED, categoryOverridden = false)
                return null
            }
        }
        assertEquals(Outcome.DONE, runner(scanNamesIt).run())
        assertEquals("Typed meanwhile", store.named.namedProduct(unknown)?.name)
        assertEquals("Collyre", store.named.namedProduct(other)?.name)
        assertEquals(false, store.named.backfillPending())
    }

    @Test
    fun runningTwiceFillsOnce() = test { location ->
        insert(location, unknown, "Arnica")
        assertEquals(Outcome.DONE, runner().run())
        val first = store.named.namedProduct(unknown)
        assertEquals(Outcome.NOT_PENDING, runner().run())
        assertEquals(first, store.named.namedProduct(unknown))
    }
}
