package com.medicabinet.app.features.cabinet

import app.cash.turbine.test
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceDataSource
import com.medicabinet.core.reference.ReferenceProduct
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.testing.InMemoryReferenceData
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The official page of a box (#56): looked up from its code, never stored, none for an unknown or withdrawn one. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoxDetailLeafletTest {
    private val kit = CabinetTestKit()
    private val page = "https://base-donnees-publique.medicaments.gouv.fr/medicament/60234100/extrait"

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    /** Answers from a table, counts the lookups, and can fail. */
    private class CountingReference(
        private val products: Map<String, ReferenceProduct> = emptyMap(),
        private val failure: Exception? = null
    ) : ReferenceDataSource {
        var lookups = 0
            private set

        override suspend fun product(gtin: String): ReferenceProduct? {
            lookups++
            failure?.let { throw it }
            return products[gtin]
        }

        override suspend fun status(): ReferenceStatus = ReferenceStatus.Available(ReferenceDataDate(2026, 10, 1))
    }

    private fun model(box: Box, reference: ReferenceDataSource) = BoxDetailViewModel(
        box.id,
        AppEnvironment(kit.store, kit.store, kit.store.named, kit.clock, reference, ZoneOffset.UTC),
        UnconfinedTestDispatcher()
    )

    private val known = mapOf(CabinetTestKit.DOLIPRANE to ReferenceProduct("DOLIPRANE 1000 mg", cis = "60234100"))

    @Test
    fun aKnownCodeGivesTheOfficialPage() = runTest {
        kit.open()
        val box = kit.box("Doliprane", CabinetTestKit.DOLIPRANE)
        model(box, CountingReference(known)).leaflet.test {
            var item = awaitItem()
            while (item == null) item = awaitItem()
            assertEquals(page, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anUnknownCodeGivesNoLink() = runTest {
        kit.open()
        val box = kit.box("Cream", CabinetTestKit.DOLIPRANE)
        val reference = CountingReference()
        val model = model(box, reference)
        model.leaflet.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, reference.lookups)
    }

    @Test
    fun aWithdrawnPresentationGivesNoLink() = runTest {
        kit.open()
        val box = kit.box("Old", CabinetTestKit.DOLIPRANE)
        val withdrawn = mapOf(CabinetTestKit.DOLIPRANE to ReferenceProduct("Old", cis = "60234100", isWithdrawn = true))
        model(box, CountingReference(withdrawn)).leaflet.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aBoxWithoutACodeIsNeverLookedUp() = runTest {
        kit.open()
        val box = kit.box("Thermometer")
        val reference = CountingReference(known)
        model(box, reference).leaflet.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, reference.lookups)
    }

    @Test
    fun aFailingLookupGivesNoLinkAndNoCrash() = runTest {
        kit.open()
        val box = kit.box("Doliprane", CabinetTestKit.DOLIPRANE)
        val reference = CountingReference(known, failure = IllegalStateException("unreadable"))
        model(box, reference).leaflet.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, reference.lookups)
    }

    @Test
    fun anEditOfTheBoxDoesNotLookTheCodeUpAgain() = runTest {
        kit.open()
        val box = kit.box("Doliprane", CabinetTestKit.DOLIPRANE)
        val reference = CountingReference(known)
        val viewModel = model(box, reference)
        viewModel.leaflet.test {
            var item = awaitItem()
            while (item == null) item = awaitItem()
            viewModel.setOpened(true)
            kit.store.update(kit.store.box(box.id)!!.copy(category = CategoryID.PAIN_FEVER))
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, reference.lookups)
    }

    @Test
    fun theKitReferenceStillAnswersForTheOtherTests() = runTest {
        // the in-memory source used elsewhere keeps working with the new fields' defaults
        val product = InMemoryReferenceData(
            mapOf("3400930000014" to ReferenceProduct("X")),
            ReferenceStatus.Available(ReferenceDataDate(2026, 10, 1))
        ).product(CabinetTestKit.DOLIPRANE)
        assertNull(product?.cis)
    }
}
