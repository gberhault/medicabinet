package com.medicabinet.app.features.cabinet

import app.cash.turbine.test
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryStatus
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

/** The category order and the filter chips of the Cabinet header. */
@OptIn(ExperimentalCoroutinesApi::class)
class CabinetViewModelFilterTest {
    private val kit = CabinetTestKit()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun model() = CabinetViewModel(kit.environment, UnconfinedTestDispatcher())

    /** The next loaded state that satisfies [until]; filters changed in a row may be reported one by one. */
    private suspend fun app.cash.turbine.ReceiveTurbine<CabinetUiState>.loaded(
        until: (CabinetUiState.Loaded) -> Boolean = { true }
    ): CabinetUiState.Loaded {
        var item = awaitItem()
        while (item !is CabinetUiState.Loaded || !until(item)) item = awaitItem()
        return item
    }

    @Test
    fun theCategoryOrderGivenByTheScreenOrdersTheSections() = runTest {
        kit.open()
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE, category = CategoryID.PAIN_FEVER)
        kit.box("Gaviscon", "03400930000021", CabinetTestKit.FINE, category = CategoryID.DIGESTION)
        val viewModel = model()
        viewModel.state.test {
            assertEquals(
                listOf(CategoryID.PAIN_FEVER, CategoryID.DIGESTION),
                loaded().snapshot.sections.map { it.category },
                "the taxonomy's order until the screen sets one"
            )
            viewModel.setCategoryOrder(listOf(CategoryID.DIGESTION, CategoryID.PAIN_FEVER))
            assertEquals(
                listOf(CategoryID.DIGESTION, CategoryID.PAIN_FEVER),
                loaded().snapshot.sections.map { it.category }
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeFilterCountCountsLocationAndStatusButNotTheSearch() = runTest {
        kit.open()
        val kitchen = kit.store.create("Kitchen")
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        val viewModel = model()
        viewModel.state.test {
            assertEquals(0, loaded().activeFilterCount)
            viewModel.setLocation(kitchen.id)
            assertEquals(1, loaded { it.activeFilterCount == 1 }.activeFilterCount)
            viewModel.setStatus(ExpiryStatus.EXPIRED)
            assertEquals(2, loaded { it.activeFilterCount == 2 }.activeFilterCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clearingTheLocationOrTheStatusLeavesTheOtherFilter() = runTest {
        kit.open()
        val kitchen = kit.store.create("Kitchen")
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE, location = kitchen)
        val viewModel = model()
        viewModel.state.test {
            loaded()
            viewModel.setLocation(kitchen.id)
            viewModel.setStatus(ExpiryStatus.OK)
            val both = loaded { it.activeFilterCount == 2 }
            assertEquals(kitchen.id, both.filter.locationId)
            assertEquals(ExpiryStatus.OK, both.filter.status)

            viewModel.setLocation(null)
            val statusOnly = loaded { it.filter.locationId == null }
            assertNull(statusOnly.filter.locationId)
            assertEquals(ExpiryStatus.OK, statusOnly.filter.status)

            viewModel.setLocation(kitchen.id)
            viewModel.setStatus(null)
            val locationOnly = loaded { it.filter.locationId != null && it.filter.status == null }
            assertNull(locationOnly.filter.status)
            assertEquals(kitchen.id, locationOnly.filter.locationId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
