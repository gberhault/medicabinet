package com.medicabinet.app.features.cabinet

import app.cash.turbine.test
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.cabinet.ProductKey
import com.medicabinet.core.expiry.ExpiryWindow
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CabinetViewModelTest {
    private val kit = CabinetTestKit()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun model(environment: com.medicabinet.app.app.AppEnvironment = kit.environment) =
        CabinetViewModel(environment, UnconfinedTestDispatcher())

    private suspend fun app.cash.turbine.ReceiveTurbine<CabinetUiState>.loaded(): CabinetUiState.Loaded {
        var item = awaitItem()
        while (item !is CabinetUiState.Loaded) item = awaitItem()
        return item
    }

    @Test
    fun startsLoadingThenShowsTheRowsGroupedByCategory() = runTest {
        kit.open()
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE, category = CategoryID.PAIN_FEVER)
        kit.box("Pansements", expiry = CabinetTestKit.SOON)
        model().state.test {
            val state = loaded()
            assertEquals(2, state.snapshot.boxCount)
            assertEquals(
                listOf(CategoryID.PAIN_FEVER, CategoryID.UNCATEGORIZED),
                state.snapshot.sections.map {
                    it.category
                }
            )
            assertFalse(state.isCabinetEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theFirstStateIsLoading() = runTest {
        kit.open()
        val states = model().state
        assertEquals(CabinetUiState.Loading, states.value)
    }

    @Test
    fun anEmptyCabinetIsReportedAsEmptyNotAsNoResults() = runTest {
        kit.open()
        model().state.test {
            val state = loaded()
            assertTrue(state.isCabinetEmpty)
            assertEquals(0, state.snapshot.boxCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aBoxWithoutExpiryIsUnknownNeverOk() = runTest {
        kit.open()
        kit.box("Pansements")
        model().state.test {
            val row = loaded().snapshot.sections.single().rows.single()
            assertEquals(ExpiryStatus.UNKNOWN, row.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theListFollowsBoxesAddedElsewhere() = runTest {
        kit.open()
        model().state.test {
            assertEquals(0, loaded().snapshot.boxCount)
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
            assertEquals(1, loaded().snapshot.boxCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theSearchWaitsOneHundredFiftyMillisecondsThenFiltersAccentInsensitively() = runTest {
        kit.open()
        kit.box("EFFÉRALGAN 500", CabinetTestKit.EFFERALGAN, CabinetTestKit.FINE)
        kit.box("Doliprane 1000", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        val viewModel = model()
        viewModel.state.test {
            assertEquals(2, loaded().snapshot.boxCount)
            viewModel.setQuery("efferalgan")
            expectNoEvents()
            testScheduler.advanceTimeBy(149)
            expectNoEvents()
            testScheduler.advanceTimeBy(2)
            val state = loaded()
            assertEquals(1, state.snapshot.boxCount)
            assertEquals("EFFÉRALGAN 500", state.snapshot.sections.single().rows.single().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clearingTheSearchAppliesAtOnce() = runTest {
        kit.open()
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        val viewModel = model()
        viewModel.state.test {
            loaded()
            viewModel.setQuery("zzz")
            testScheduler.advanceTimeBy(200)
            assertEquals(0, loaded().snapshot.boxCount)
            viewModel.setQuery("")
            assertEquals(1, loaded().snapshot.boxCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theLocationFilterCountsOnlyThatLocationsBoxes() = runTest {
        kit.open()
        val kitchen = kit.store.create("Kitchen")
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON, location = kitchen)
        val viewModel = model()
        viewModel.state.test {
            assertEquals(2, loaded().snapshot.boxCount)
            viewModel.setLocation(kitchen.id)
            val state = loaded()
            assertEquals(1, state.snapshot.boxCount)
            assertEquals(kitchen.id, state.filter.locationId)
            assertTrue(state.filter.isActive)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theStatusFilterKeepsRowsWithABoxInThatStatus() = runTest {
        kit.open()
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.EXPIRED)
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        kit.box("Pansements", expiry = CabinetTestKit.FINE)
        val viewModel = model()
        viewModel.state.test {
            loaded()
            viewModel.setStatus(ExpiryStatus.EXPIRED)
            val state = loaded()
            assertEquals(1, state.snapshot.boxCount)
            assertEquals(ExpiryStatus.EXPIRED, state.snapshot.sections.single().rows.single().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun noMatchGivesAnEmptySnapshotAndClearFiltersRestoresTheList() = runTest {
        kit.open()
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        val viewModel = model()
        viewModel.state.test {
            loaded()
            viewModel.setStatus(ExpiryStatus.EXPIRED)
            val none = loaded()
            assertTrue(none.snapshot.sections.isEmpty())
            assertFalse(none.isCabinetEmpty)
            viewModel.clearFilters()
            val all = loaded()
            assertEquals(1, all.snapshot.boxCount)
            assertFalse(all.filter.isActive)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aFilterOnADeletedLocationFallsBackToAllLocations() = runTest {
        kit.open()
        val garage = kit.store.create("Garage")
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        val viewModel = model()
        viewModel.state.test {
            loaded()
            viewModel.setLocation(garage.id)
            assertEquals(garage.id, loaded().filter.locationId)
            kit.store.deleteLocation(garage.id, null)
            val state = loaded()
            assertNull(state.filter.locationId)
            assertEquals(1, state.snapshot.boxCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anUnreadableCabinetFailsAndRetryTriesAgain() = runTest {
        kit.open()
        val viewModel = model(kit.failingEnvironment())
        viewModel.state.test {
            assertEquals(CabinetUiState.Failed, awaitItem())
            viewModel.retry()
            assertEquals(CabinetUiState.Loading, awaitItem())
            assertEquals(CabinetUiState.Failed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theDayChangeRecomputesTheStatuses() = runTest {
        kit.open()
        // Expires 2027-02-10: expiring soon on 2027-01-15, expired on 2027-02-11.
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON)
        val viewModel = model()
        viewModel.state.test {
            assertEquals(ExpiryStatus.EXPIRING_SOON, loaded().snapshot.sections.single().rows.single().status)
            kit.clock.advance(Duration.ofDays(27))
            viewModel.onForeground()
            val state = loaded()
            assertEquals(ExpiryStatus.EXPIRED, state.snapshot.sections.single().rows.single().status)
            assertEquals(2027, state.today.year)
            assertEquals(2, state.today.month)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun statusesAreReevaluatedAtMidnightWhileVisible() = runTest {
        kit.open()
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON)
        val viewModel = model()
        viewModel.state.test {
            loaded()
            // The test clock is not tied to virtual time: move it past the expiry, then let the midnight timer fire.
            kit.clock.advance(Duration.ofDays(27))
            testScheduler.advanceTimeBy(Duration.ofDays(1).toMillis())
            assertEquals(ExpiryStatus.EXPIRED, loaded().snapshot.sections.single().rows.single().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun rowsKeepTheProductKeyOfTheirCode() = runTest {
        kit.open()
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        model().state.test {
            val key = loaded().snapshot.sections.single().rows.single().id.key
            assertIs<ProductKey.Gtin>(key)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun rowsAreBuiltOnTheInjectedDispatcher() = runTest {
        kit.open()
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = CabinetViewModel(kit.environment, dispatcher)
        viewModel.state.test {
            assertEquals(CabinetUiState.Loading, awaitItem())
            testScheduler.advanceTimeBy(1)
            assertIs<CabinetUiState.Loaded>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aNewExpiryWindowEvaluatesTheStatusesAgain() = runTest {
        kit.open()
        // 45 days left on 2027-01-15: expiring soon in a 60-day window, fine in a 30-day one.
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, ExpiryDate.of(2027, 3, 1))
        model().state.test {
            val first = loaded()
            assertEquals(ExpiryStatus.EXPIRING_SOON, first.snapshot.sections.single().rows.single().status)
            assertEquals(ExpiryWindow.DAYS_60, first.window)
            kit.environment.expiryPreferences.window = ExpiryWindow.DAYS_30
            val second = loaded()
            assertEquals(ExpiryStatus.OK, second.snapshot.sections.single().rows.single().status)
            assertEquals(ExpiryWindow.DAYS_30, second.window)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
