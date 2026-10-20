package com.medicabinet.core.named

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.Location
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.reference.ProductLookup
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.scan.BoxDraft
import com.medicabinet.core.scan.ScanIntake
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import com.medicabinet.core.testing.MutableClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ProductMemoryTest {
    private val gtin = "03401900000003"

    // A code the in-memory reference names: reference keys are the CIP13 behind a GTIN.
    private val knownGtin = "03400935955838"
    private val knownCip13 = "3400935955838"
    private val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
    private val store = InMemoryCabinetStore(clock)
    private val available = ReferenceStatus.Available(ReferenceDataDate(2026, 10, 18))
    private val unavailable = ReferenceStatus.Unavailable(ReferenceUnavailableReason.UNREADABLE)

    private fun box(location: Location, name: String = "Crème solaire", gtin: String? = this.gtin) = Box(
        product = ProductRef(gtin, name),
        locationId = location.id,
        createdAt = clock.now()
    )

    private fun memory(
        status: ReferenceStatus = available,
        products: Map<String, ReferenceProduct> = emptyMap(),
        named: NamedProductRepository = store.named
    ) = ProductMemory(named, InMemoryReferenceData(products, status))

    private fun test(body: suspend (Location) -> Unit) = runBlocking { body(store.ensureDefault("Cabinet")) }

    @Test
    fun anAddedBoxOfAnUnknownCodeIsRemembered() = test { location ->
        memory().record(MemoryEvent.Added(box(location)))
        assertEquals("Crème solaire", store.named.namedProduct(gtin)?.name)
    }

    @Test
    fun anUnavailableReferenceWritesNothingBecauseUnknownIsNotAFact() = test { location ->
        memory(status = unavailable).record(MemoryEvent.Added(box(location)))
        assertNull(store.named.namedProduct(gtin))
    }

    @Test
    fun aLookupThatThrowsWritesNothing() = test { location ->
        val failing = object : ProductLookup {
            override suspend fun product(gtin: String): ReferenceProduct? =
                throw RepositoryException(RepositoryError.NOT_FOUND)
        }
        ProductMemory(store.named, failing).record(MemoryEvent.Added(box(location)))
        assertNull(store.named.namedProduct(gtin))
    }

    @Test
    fun aCodeTheReferenceKnowsIsNeverRemembered() = test { location ->
        memory(products = mapOf(knownCip13 to ReferenceProduct("PRODUIT")))
            .record(MemoryEvent.Added(box(location, gtin = knownGtin)))
        assertNull(store.named.namedProduct(knownGtin))
    }

    @Test
    fun aBoxWithoutACodeIsNeverRemembered() = test { location ->
        var asked = false
        val lookup = object : ProductLookup {
            override suspend fun product(gtin: String): ReferenceProduct? {
                asked = true
                return null
            }
        }
        ProductMemory(store.named, lookup).record(MemoryEvent.Added(box(location, gtin = null)))
        assertEquals(false, asked)
        assertEquals(emptyList(), store.named.observeSummariesNow())
    }

    @Test
    fun anEditThatKeepsTheNameAndCategoryWritesNothing() = test { location ->
        val before = box(location)
        val after = before.copy(isOpened = true)
        memory().record(MemoryEvent.Changed(before, after))
        assertNull(store.named.namedProduct(gtin))
    }

    @Test
    fun aNewNameUpdatesTheRememberedProduct() = test { location ->
        val before = box(location)
        memory().record(MemoryEvent.Added(before))
        memory().record(MemoryEvent.SessionCorrected(before, before.copy(product = ProductRef(gtin, "Crème SPF50"))))
        assertEquals("Crème SPF50", store.named.namedProduct(gtin)?.name)
    }

    @Test
    fun aFailingWriteIsReportedToTheCaller() = test { location ->
        val failing = object : NamedProductRepository by store.named {
            override suspend fun remember(
                gtin: String,
                name: String,
                category: CategoryID,
                categoryOverridden: Boolean
            ): Unit = throw IllegalStateException("disk full")
        }
        assertFailsWith<IllegalStateException> {
            memory(named = failing).record(MemoryEvent.Added(box(location)))
        }
    }

    @Test
    fun aFailingWriteNeverUndoesTheBoxNorStopsIntake() = test { location ->
        val failing = object : NamedProductRepository by store.named {
            override suspend fun remember(
                gtin: String,
                name: String,
                category: CategoryID,
                categoryOverridden: Boolean
            ): Unit = throw IllegalStateException("disk full")
        }
        val reported = mutableListOf<Exception>()
        val intake = ScanIntake(store, memory(named = failing), clock) { reported += it }
        val saved = intake.add(BoxDraft(gtin, "Crème solaire"), location.id)
        assertEquals(saved, store.box(saved.id))
        assertEquals(listOf("IllegalStateException"), reported.map { it::class.java.simpleName })
    }

    @Test
    fun theIntakeRemembersTheNameOfANewBoxOfAnUnknownCode() = test { location ->
        val intake = ScanIntake(store, memory(), clock)
        intake.add(BoxDraft(gtin, "Crème solaire", CategoryID.SKIN_WOUND, categoryOverridden = true), location.id)
        val remembered = assertNotNull(store.named.namedProduct(gtin))
        assertEquals(CategoryID.SKIN_WOUND, remembered.category)
        assertEquals(true, remembered.categoryOverridden)
    }

    @Test
    fun theIntakeRemembersNothingWhileTheReferenceIsUnavailable() = test { location ->
        ScanIntake(store, memory(status = unavailable), clock).add(BoxDraft(gtin, "Crème solaire"), location.id)
        assertNull(store.named.namedProduct(gtin))
    }

    @Test
    fun resolveGoesReferenceThenNamedProductThenAsks() = test {
        val product = memory(products = mapOf(knownCip13 to ReferenceProduct("PRODUIT", CategoryID.EYES)))
        assertEquals("PRODUIT", product.resolve(knownGtin, previousBox = null).name)
        store.named.remember("03401900000010", "Arnica", CategoryID.SKIN_WOUND, categoryOverridden = true)
        val named = memory().resolve("03401900000010", previousBox = null)
        assertEquals("Arnica", named.name)
        assertEquals(CategoryID.SKIN_WOUND, named.category)
        assertNull(memory().resolve("03401900000027", previousBox = null).name)
    }
}

private suspend fun NamedProductRepository.observeSummariesNow() = observeSummaries().first()
