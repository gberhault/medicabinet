package com.medicabinet.app.features.expiring

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.medicabinet.app.features.cabinet.CabinetTestKit
import com.medicabinet.core.Box
import com.medicabinet.core.BoxRepository
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.expiry.ExpiryWindow
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
class ExpiringViewModelTest {
    private val kit = CabinetTestKit()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun model(environment: com.medicabinet.app.app.AppEnvironment = kit.environment) =
        ExpiringViewModel(environment, UnconfinedTestDispatcher())

    private suspend fun ReceiveTurbine<ExpiringUiState>.content(): ExpiringUiState.Content {
        var item = awaitItem()
        while (item !is ExpiringUiState.Content) item = awaitItem()
        return item
    }

    private suspend fun ReceiveTurbine<ExpiringUiState>.empty(): ExpiringUiState.Empty {
        var item = awaitItem()
        while (item !is ExpiringUiState.Empty) item = awaitItem()
        return item
    }

    private fun ExpiringUiState.Content.names(entries: List<com.medicabinet.core.expiry.ExpiringEntry>) =
        entries.map { it.box.product.name }

    @Test
    fun theFirstStateIsLoading() = runTest {
        kit.open()
        assertEquals(ExpiringUiState.Loading, model().state.value)
    }

    @Test
    fun showsExpiredExpiringSoonAndUnknownBoxesInOrderAndNeverTheOkOnes() = runTest {
        kit.open()
        val kitchen = kit.store.create("Kitchen")
        kit.box("Smecta", expiry = CabinetTestKit.EXPIRED)
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON, kitchen)
        kit.box("Spasfon", expiry = CabinetTestKit.FINE)
        kit.box("Pansements")
        model().state.test {
            val state = content()
            assertEquals(listOf("Smecta"), state.names(state.overview.expired))
            assertEquals(listOf("Doliprane"), state.names(state.overview.expiringSoon))
            assertEquals(listOf("Pansements"), state.names(state.overview.unknown))
            assertEquals(ExpiryWindow.DAYS_60, state.window)
            assertEquals("Kitchen", state.locationNames[kitchen.id])
            assertEquals("Home", state.locationNames[kit.home.id])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anUnknownExpiryIsListedAsUnknownNeverAsOk() = runTest {
        kit.open()
        kit.box("Pansements")
        model().state.test {
            val state = content()
            assertEquals(ExpiryStatus.UNKNOWN, state.overview.unknown.single().assessment.status)
            assertTrue(state.overview.expired.isEmpty() && state.overview.expiringSoon.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anEmptyOverviewNamesTheWindow() = runTest {
        kit.open()
        kit.box("Spasfon", expiry = CabinetTestKit.FINE)
        val model = model()
        model.state.test {
            assertEquals(ExpiryWindow.DAYS_60, empty().window)
            kit.environment.expiryPreferences.window = ExpiryWindow.DAYS_90
            assertEquals(ExpiryWindow.DAYS_90, empty().window)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theBadgeCountsExpiredAndExpiringSoonButNotUnknown() = runTest {
        kit.open()
        kit.box("Smecta", expiry = CabinetTestKit.EXPIRED)
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON)
        kit.box("Pansements")
        val model = model()
        model.attentionCount.test {
            var count = awaitItem()
            while (count != 2) count = awaitItem()
            assertEquals(2, count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theBadgeIsZeroWhenNothingNeedsAttention() = runTest {
        kit.open()
        kit.box("Pansements")
        model().attentionCount.test {
            assertEquals(0, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theListFollowsTheCabinetWithoutARefresh() = runTest {
        kit.open()
        kit.box("Smecta", expiry = CabinetTestKit.EXPIRED)
        model().state.test {
            assertEquals(1, content().overview.expired.size)
            val added = kit.box("Spasfon", expiry = CabinetTestKit.EXPIRED)
            assertEquals(2, content().overview.expired.size)
            kit.store.markRemoved(added.id, RemovalReason.USED_UP, kit.clock.now())
            assertEquals(1, content().overview.expired.size)
            kit.store.undoRemoval(added.id)
            assertEquals(2, content().overview.expired.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aNewWindowMovesBoxesBetweenSections() = runTest {
        kit.open()
        // 45 days left on 2027-01-15: expiring soon in the 60-day window, not in the 30-day one.
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, ExpiryDate.of(2027, 3, 1))
        model().state.test {
            assertEquals(1, content().overview.expiringSoon.size)
            kit.environment.expiryPreferences.window = ExpiryWindow.DAYS_30
            assertEquals(ExpiryWindow.DAYS_30, empty().window)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theDayChangeMovesAnExpiringBoxToExpired() = runTest {
        kit.open()
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON)
        model().state.test {
            assertEquals(1, content().overview.expiringSoon.size)
            // The test clock is not tied to virtual time: move it past the expiry, then let the midnight timer fire.
            kit.clock.advance(Duration.ofDays(27))
            testScheduler.advanceTimeBy(Duration.ofDays(1).toMillis())
            val state = content()
            assertEquals(1, state.overview.expired.size)
            assertTrue(state.overview.expiringSoon.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anExpiryTodayIsExpiringSoonAndSaysSo() = runTest {
        kit.open()
        kit.box("Doliprane", CabinetTestKit.DOLIPRANE, ExpiryDate.of(2027, 1, 15))
        model().state.test {
            val entry = content().overview.expiringSoon.single()
            assertTrue(entry.assessment.expiresToday)
            assertEquals(ExpiryStatus.EXPIRING_SOON, entry.assessment.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anUnreadableCabinetFailsAndRetryReloads() = runTest {
        kit.open()
        val model = model(kit.failingEnvironment())
        model.state.test {
            assertEquals(ExpiringUiState.Failed, awaitItemAfterLoading())
            model.retry()
            // Retry loads again; still unreadable, so the error state comes back.
            assertEquals(ExpiringUiState.Loading, awaitItem())
            assertEquals(ExpiringUiState.Failed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun retryRecoversWhenTheCabinetCanBeReadAgain() = runTest {
        kit.open()
        kit.box("Smecta", expiry = CabinetTestKit.EXPIRED)
        var broken = true
        val flaky = object : BoxRepository by kit.store {
            override fun observeCabinet(): Flow<List<Box>> =
                if (broken) flow { throw IllegalStateException("unreadable") } else kit.store.observeCabinet()
        }
        val model = model(
            com.medicabinet.app.app.AppEnvironment(
                kit.store,
                flaky,
                kit.store.named,
                kit.clock,
                kit.environment.reference,
                kit.environment.zone
            )
        )
        model.state.test {
            assertEquals(ExpiringUiState.Failed, awaitItemAfterLoading())
            broken = false
            model.retry()
            assertEquals(1, content().overview.expired.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun ReceiveTurbine<ExpiringUiState>.awaitItemAfterLoading(): ExpiringUiState {
        var item = awaitItem()
        while (item == ExpiringUiState.Loading) item = awaitItem()
        return item
    }

    @Test
    fun throwingAwayMarksTheBoxRemovedWithThatReasonAtTheClockTime() = runTest {
        kit.open()
        val box = kit.box("Smecta", expiry = CabinetTestKit.EXPIRED)
        kit.clock.advance(Duration.ofHours(1))
        val now = kit.clock.now()
        val model = model()
        model.state.test {
            content()
            assertTrue(model.throwAway(box))
            assertEquals(ExpiringUiState.Empty(ExpiryWindow.DAYS_60), empty())
            cancelAndIgnoreRemainingEvents()
        }
        val removal = assertNotNull(kit.store.box(box.id)?.removal)
        assertEquals(RemovalReason.THROWN_AWAY, removal.reason)
        assertEquals(now, removal.date)
    }

    @Test
    fun undoPutsTheBoxBackInTheList() = runTest {
        kit.open()
        val box = kit.box("Smecta", expiry = CabinetTestKit.EXPIRED)
        val model = model()
        model.state.test {
            content()
            assertTrue(model.throwAway(box))
            empty()
            assertTrue(model.undoThrowAway(box.id))
            assertEquals(listOf("Smecta"), content().let { it.names(it.overview.expired) })
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(kit.store.box(box.id)?.removal)
    }

    @Test
    fun aFailedWriteLeavesTheBoxUnchangedAndSaysSo() = runTest {
        kit.open()
        val box = kit.box("Smecta", expiry = CabinetTestKit.EXPIRED)
        val model = model(kit.readOnlyEnvironment())
        model.state.test {
            assertEquals(1, content().overview.expired.size)
            assertFalse(model.throwAway(box))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(kit.store.box(box.id)?.removal)
    }

    @Test
    fun anUndoThatFailsReportsIt() = runTest {
        kit.open()
        // An unknown box cannot be restored: the repository refuses.
        assertFalse(model().undoThrowAway(java.util.UUID.randomUUID()))
    }

    @Test
    fun theStateTypesAreWhatTheScreenExpects() = runTest {
        kit.open()
        model().state.test {
            assertIs<ExpiringUiState>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
