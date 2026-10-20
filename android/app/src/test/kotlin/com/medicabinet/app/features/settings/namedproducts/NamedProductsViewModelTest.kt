package com.medicabinet.app.features.settings.namedproducts

import app.cash.turbine.test
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.FixedClock
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.NamedProductSummary
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.named.NamedProductEdit
import com.medicabinet.core.named.NamedProductEditError
import com.medicabinet.core.testing.InMemoryCabinetStore
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NamedProductsViewModelTest {
    private val clock = FixedClock(Instant.ofEpochSecond(1_800_000_000))
    private val store = InMemoryCabinetStore(clock)
    private val named get() = store.named
    private val sun = "03401900000003"
    private val arnica = "03401900000010"

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun NamedProductsViewModel.rows(): List<NamedProductSummary> =
        (state.value as NamedProductsUiState.Loaded).rows

    private suspend fun name(gtin: String, name: String, category: CategoryID = CategoryID.UNCATEGORIZED) =
        named.remember(gtin, name, category, categoryOverridden = category != CategoryID.UNCATEGORIZED)

    private suspend fun boxes(gtin: String, count: Int, name: String = "Box"): List<Box> {
        val location = store.ensureDefault("Cabinet")
        return List(count) {
            Box(
                product = ProductRef(gtin, name),
                locationId = location.id,
                serial = UUID.randomUUID().toString().take(12),
                createdAt = clock.now()
            ).also { store.insert(it) }
        }
    }

    private suspend fun row(gtin: String): NamedProductSummary =
        NamedProductSummary(checkNotNull(named.namedProduct(gtin)), 0)

    private suspend fun edit(gtin: String): NamedProductEdit = NamedProductEdit.from(row(gtin).product)

    @Test
    fun theStateGoesFromLoadingToLoadedAndTheListIsOrderedByNameThenCode() = runTest {
        name(sun, "Zinc")
        name(arnica, "arnica")
        name("03401900000027", "Écran total")
        val viewModel = NamedProductsViewModel(named)
        assertEquals(listOf("arnica", "Écran total", "Zinc"), viewModel.rows().map { it.product.name })
    }

    @Test
    fun theStateEmitsTheListAndThenEveryChangeOfEitherTable() = runTest {
        val viewModel = NamedProductsViewModel(named)
        viewModel.state.test {
            assertEquals(NamedProductsUiState.Loaded(emptyList()), awaitItem())
            name(sun, "Crème solaire")
            assertEquals(listOf(0), (awaitItem() as NamedProductsUiState.Loaded).rows.map { it.boxesInCabinet })
            boxes(sun, 1)
            assertEquals(listOf(1), (awaitItem() as NamedProductsUiState.Loaded).rows.map { it.boxesInCabinet })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun startsInLoadingBeforeTheFirstList() = runTest {
        val never = object : NamedProductRepository by named {
            override fun observeSummaries(): Flow<List<NamedProductSummary>> =
                flow { kotlinx.coroutines.awaitCancellation() }
        }
        NamedProductsViewModel(never).state.test {
            assertEquals(NamedProductsUiState.Loading, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anUnreadableListFailsAndRetryReadsItAgain() = runTest {
        var broken = true
        val flaky = object : NamedProductRepository by named {
            override fun observeSummaries(): Flow<List<NamedProductSummary>> =
                if (broken) flow { throw IllegalStateException("unreadable") } else named.observeSummaries()
        }
        name(sun, "Crème solaire")
        val viewModel = NamedProductsViewModel(flaky)
        assertEquals(NamedProductsUiState.Failed, viewModel.state.value)
        broken = false
        viewModel.observe()
        assertEquals(listOf("Crème solaire"), viewModel.rows().map { it.product.name })
    }

    @Test
    fun everyRowCountsOnlyTheBoxesStillInTheCabinet() = runTest {
        name(sun, "Crème solaire")
        name(arnica, "Arnica")
        boxes(sun, 2)
        val gone = boxes(sun, 1).single()
        store.markRemoved(gone.id, RemovalReason.USED_UP, clock.now())
        val viewModel = NamedProductsViewModel(named)
        assertEquals(listOf(arnica to 0, sun to 2), viewModel.rows().map { it.product.gtin to it.boxesInCabinet })
    }

    @Test
    fun theListFollowsAChangeMadeElsewhere() = runTest {
        val viewModel = NamedProductsViewModel(named)
        assertEquals(emptyList(), viewModel.rows())
        name(sun, "Crème solaire")
        assertEquals(1, viewModel.rows().size)
        boxes(sun, 1)
        assertEquals(1, viewModel.rows().single().boxesInCabinet)
    }

    // save

    @Test
    fun renamingChangesTheProductAndItsBoxesTrimmed() = runTest {
        name(sun, "Test")
        val boxes = boxes(sun, 2, name = "Test")
        val viewModel = NamedProductsViewModel(named)
        val result = viewModel.save(edit(sun).copy(name = "  Crème solaire "))
        assertEquals(SaveOutcome.Saved, result)
        assertEquals("Crème solaire", viewModel.rows().single().product.name)
        assertEquals(listOf("Crème solaire", "Crème solaire"), boxes.map { store.box(it.id)?.product?.name })
    }

    @Test
    fun aCategoryOnlyChangeRecategorisesTheProductAndItsBoxes() = runTest {
        name(sun, "Crème solaire")
        val box = boxes(sun, 1).single()
        val viewModel = NamedProductsViewModel(named)
        assertEquals(SaveOutcome.Saved, viewModel.save(edit(sun).chooseCategory(CategoryID.SKIN_WOUND)))
        val product = viewModel.rows().single().product
        assertEquals("Crème solaire", product.name)
        assertEquals(CategoryID.SKIN_WOUND, product.category)
        assertTrue(product.categoryOverridden)
        assertEquals(CategoryID.SKIN_WOUND, store.box(box.id)?.category)
    }

    @Test
    fun aNameAndACategoryChangeTogether() = runTest {
        name(sun, "Test")
        val viewModel = NamedProductsViewModel(named)
        val result = viewModel.save(edit(sun).copy(name = "Collyre").chooseCategory(CategoryID.EYES))
        assertEquals(SaveOutcome.Saved, result)
        val product = viewModel.rows().single().product
        assertEquals("Collyre", product.name)
        assertEquals(CategoryID.EYES, product.category)
    }

    @Test
    fun theAutomaticCategoryGivesTheCategoryBack() = runTest {
        name(sun, "Crème solaire", CategoryID.SKIN_WOUND)
        val viewModel = NamedProductsViewModel(named)
        viewModel.save(edit(sun).resetCategory())
        val product = viewModel.rows().single().product
        assertEquals(CategoryID.UNCATEGORIZED, product.category)
        assertFalse(product.categoryOverridden)
    }

    @Test
    fun anInvalidNameWritesNothingAndSaysWhy() = runTest {
        name(sun, "Test")
        val viewModel = NamedProductsViewModel(named)
        assertEquals(SaveOutcome.Invalid(NamedProductEditError.EMPTY_NAME), viewModel.save(edit(sun).copy(name = " ")))
        assertEquals(
            SaveOutcome.Invalid(NamedProductEditError.NAME_TOO_LONG),
            viewModel.save(edit(sun).copy(name = "x".repeat(201)))
        )
        assertEquals("Test", viewModel.rows().single().product.name)
        assertEquals(NamedProductEditError.EMPTY_NAME, viewModel.nameProblem(edit(sun).copy(name = "")))
        assertNull(viewModel.nameProblem(edit(sun)))
    }

    @Test
    fun anUntouchedFormWritesNothing() = runTest {
        name(sun, "Test")
        val before = row(sun).product
        clock.advance(java.time.Duration.ofSeconds(60))
        val viewModel = NamedProductsViewModel(named)
        assertEquals(SaveOutcome.Saved, viewModel.save(edit(sun)))
        assertEquals(before, row(sun).product)
    }

    @Test
    fun aFailingWriteIsReportedAndTheListStaysAsItWas() = runTest {
        name(sun, "Test")
        val failing = object : NamedProductRepository by named {
            override suspend fun rename(id: UUID, name: String) = throw IllegalStateException("disk full")
        }
        val viewModel = NamedProductsViewModel(failing)
        assertEquals(SaveOutcome.Failed, viewModel.save(edit(sun).copy(name = "Collyre")))
        assertEquals("Test", viewModel.rows().single().product.name)
    }

    // forget

    @Test
    fun theForgetRequestCarriesTheBoxesStillInTheCabinetForItsMessage() = runTest {
        name(sun, "Zero")
        name(arnica, "One")
        name("03401900000027", "Two")
        boxes(arnica, 1)
        boxes("03401900000027", 2)
        val viewModel = NamedProductsViewModel(named)
        val counts = viewModel.rows().map { it.product.name to it.boxesInCabinet }
        assertEquals(listOf("One" to 1, "Two" to 2, "Zero" to 0), counts)
        for (row in viewModel.rows()) {
            viewModel.requestForget(row)
            assertEquals(row.boxesInCabinet, viewModel.forgetRequest.value?.boxesInCabinet)
            viewModel.cancelForget()
            assertNull(viewModel.forgetRequest.value)
        }
    }

    @Test
    fun cancellingForgetsNothing() = runTest {
        name(sun, "Test")
        val viewModel = NamedProductsViewModel(named)
        viewModel.requestForget(viewModel.rows().single())
        viewModel.cancelForget()
        viewModel.confirmForget()
        assertEquals(1, viewModel.rows().size)
        assertNull(viewModel.undoOffer.value)
    }

    @Test
    fun confirmingForgetsTheProductKeepsItsBoxesAndOffersUndo() = runTest {
        name(sun, "Test")
        val box = boxes(sun, 1, name = "Test").single()
        val viewModel = NamedProductsViewModel(named)
        viewModel.requestForget(viewModel.rows().single())
        viewModel.confirmForget()
        assertEquals(emptyList(), viewModel.rows())
        assertNull(viewModel.forgetRequest.value)
        assertEquals("Test", store.box(box.id)?.product?.name)
        val offer = assertNotNull(viewModel.undoOffer.value)
        assertEquals("Test", offer.productName)
        assertEquals(ForgetUndoOutcome.RESTORED, offer.undo())
        assertEquals(listOf("Test"), viewModel.rows().map { it.product.name })
        viewModel.undoOfferShown()
        assertNull(viewModel.undoOffer.value)
    }

    @Test
    fun undoIsRefusedWhenTheCodeWasNamedAgain() = runTest {
        name(sun, "Old")
        val viewModel = NamedProductsViewModel(named)
        viewModel.requestForget(viewModel.rows().single())
        viewModel.confirmForget()
        name(sun, "New")
        assertEquals(ForgetUndoOutcome.NAMED_AGAIN, assertNotNull(viewModel.undoOffer.value).undo())
        assertEquals(listOf("New"), viewModel.rows().map { it.product.name })
    }

    @Test
    fun aFailingUndoIsReportedAsFailed() = runTest {
        name(sun, "Test")
        val failing = object : NamedProductRepository by named {
            override suspend fun undoForget(id: UUID) = throw RepositoryException(RepositoryError.NOT_FOUND)
        }
        val viewModel = NamedProductsViewModel(failing)
        viewModel.requestForget(viewModel.rows().single())
        viewModel.confirmForget()
        assertEquals(ForgetUndoOutcome.FAILED, assertNotNull(viewModel.undoOffer.value).undo())
    }

    @Test
    fun aFailingForgetKeepsTheProductAndRaisesTheAlert() = runTest {
        name(sun, "Test")
        val failing = object : NamedProductRepository by named {
            override suspend fun forget(id: UUID) = throw IllegalStateException("disk full")
        }
        val viewModel = NamedProductsViewModel(failing)
        viewModel.requestForget(viewModel.rows().single())
        viewModel.confirmForget()
        assertEquals(1, viewModel.rows().size)
        assertNull(viewModel.undoOffer.value)
        assertTrue(viewModel.forgetFailed.value)
        viewModel.acknowledgeForgetFailure()
        assertFalse(viewModel.forgetFailed.value)
    }
}
