package com.medicabinet.app.features.scan

import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** What a scan remembers as the product the household named (#57). */
class ScanViewModelMemoryTest {
    private val unknown = "03401900000003"
    private val available = ReferenceStatus.Available(ReferenceDataDate(2026, 10, 18))
    private lateinit var kit: ScanTestKit

    private fun run(status: ReferenceStatus = available, body: suspend () -> Unit) = runBlocking {
        kit = ScanTestKit(StubLookup(), referenceStatus = status).open()
        body()
    }

    private val named: NamedProductRepository get() = kit.store.named

    private suspend fun addUnknown(name: String = "Test") {
        kit.see(ScanTestKit.payload(gtin = unknown))
        kit.model.commit((kit.model.state.value.completion ?: error("a name was asked")).draft.copy(name = name))
    }

    @Test
    fun aNameTypedForAnUnknownCodeIsRemembered() = run {
        addUnknown("Crème solaire")
        assertEquals("Crème solaire", named.namedProduct(unknown)?.name)
    }

    @Test
    fun nothingIsRememberedWhileTheReferenceIsUnavailable() = run(
        ReferenceStatus.Unavailable(com.medicabinet.core.reference.ReferenceUnavailableReason.MISSING)
    ) {
        addUnknown("Crème solaire")
        assertNull(named.namedProduct(unknown))
    }

    @Test
    fun aCorrectedNameInTheSessionListIsRemembered() = run {
        addUnknown("Test")
        val box = kit.storedBoxes().single()
        assertTrue(kit.model.update(box, "Crème solaire", box.expiry, box.locationId))
        assertEquals("Crème solaire", named.namedProduct(unknown)?.name)
    }

    @Test
    fun aCorrectionOfTheExpiryAloneDoesNotRecordAnything() = run {
        addUnknown("Crème solaire")
        val remembered = assertNotNull(named.namedProduct(unknown))
        kit.clock.advance(java.time.Duration.ofSeconds(60))
        val box = kit.storedBoxes().single()
        assertTrue(kit.model.update(box, box.product.name, ScanTestKit.date(2030, 1, 31), box.locationId))
        assertEquals(remembered, named.namedProduct(unknown))
    }

    @Test
    fun aCorrectionOfAKnownCodeNeverRemembersAnything() = run {
        kit = ScanTestKit(
            StubLookup(mapOf(ScanTestKit.GTIN to com.medicabinet.core.reference.ReferenceProduct("DOLIPRANE"))),
            referenceStatus = available
        ).open()
        kit.see(ScanTestKit.payload())
        val box = kit.storedBoxes().single()
        assertTrue(kit.model.update(box, "Doliprane maison", box.expiry, box.locationId))
        assertNull(named.namedProduct(ScanTestKit.GTIN))
    }

    @Test
    fun aFailingMemoryNeverFailsTheCorrection() = run {
        addUnknown("Test")
        val failing = object : NamedProductRepository by named {
            override suspend fun remember(
                gtin: String,
                name: String,
                category: CategoryID,
                categoryOverridden: Boolean
            ): Unit = throw IllegalStateException("disk full")
        }
        val model = ScanViewModel(
            com.medicabinet.app.app.AppEnvironment(
                kit.store,
                kit.store,
                failing,
                kit.clock,
                kit.environment.reference
            ),
            kit.services
        )
        model.load()
        val box = kit.storedBoxes().single()
        assertTrue(model.update(box, "Crème solaire", box.expiry, box.locationId))
        assertEquals("Crème solaire", kit.store.box(box.id)?.product?.name)
        assertEquals("Test", named.namedProduct(unknown)?.name)
    }
}
