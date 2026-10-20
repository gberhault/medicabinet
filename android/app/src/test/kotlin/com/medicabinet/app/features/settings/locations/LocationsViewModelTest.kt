package com.medicabinet.app.features.settings.locations

import com.medicabinet.core.Box
import com.medicabinet.core.FixedClock
import com.medicabinet.core.Location
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.ProductRef
import com.medicabinet.core.testing.InMemoryCabinetStore
import java.time.Instant
import java.util.UUID
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
class LocationsViewModelTest {
    private val clock = FixedClock(Instant.ofEpochSecond(1_800_000_000))
    private val store = InMemoryCabinetStore(clock)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private suspend fun home(): Location = store.ensureDefault("Home")

    private fun LocationsViewModel.names(): List<String> =
        (state.value as LocationsUiState.Loaded).locations.map { it.name }

    private fun LocationsViewModel.locations(): List<Location> = (state.value as LocationsUiState.Loaded).locations

    private fun box(location: Location) = Box(
        product = ProductRef("03400930000014", "Doliprane"),
        locationId = location.id,
        serial = UUID.randomUUID().toString().take(12),
        createdAt = clock.now()
    )

    @Test
    fun theListLoadsAndFollowsChangesMadeElsewhere() = runTest {
        home()
        val viewModel = LocationsViewModel(store)
        assertEquals(listOf("Home"), viewModel.names())
        store.create("Garage")
        assertEquals(listOf("Home", "Garage"), viewModel.names())
    }

    @Test
    fun startsInLoadingBeforeTheFirstList() = runTest {
        val never = object : LocationRepository by store {
            override fun observeAll(): Flow<List<Location>> = flow { kotlinx.coroutines.awaitCancellation() }
        }
        assertEquals(LocationsUiState.Loading, LocationsViewModel(never).state.value)
    }

    @Test
    fun addingALocationPutsItLast() = runTest {
        home()
        val viewModel = LocationsViewModel(store)
        assertNull(viewModel.add("  Kitchen "))
        assertEquals(listOf("Home", "Kitchen"), viewModel.names())
    }

    @Test
    fun aRefusedNameSaysWhyAndChangesNothing() = runTest {
        home()
        val viewModel = LocationsViewModel(store)
        assertEquals(NameProblem.EMPTY, viewModel.add("   "))
        assertEquals(NameProblem.TOO_LONG, viewModel.add("a".repeat(51)))
        assertEquals(NameProblem.DUPLICATE, viewModel.add(" hôme "))
        assertEquals(listOf("Home"), viewModel.names())
        assertFalse(viewModel.writes.failed.value)
    }

    @Test
    fun renamingKeepsThePlaceAndAllowsACaseChangeOfItsOwnName() = runTest {
        home()
        val viewModel = LocationsViewModel(store)
        viewModel.add("Garage")
        val garage = viewModel.locations().last()
        assertNull(viewModel.rename(garage, "GARAGE"))
        assertEquals(listOf("Home", "GARAGE"), viewModel.names())
        assertEquals(NameProblem.DUPLICATE, viewModel.rename(garage, "home"))
    }

    @Test
    fun aDuplicateDecidedByTheRepositoryAfterARaceIsReportedNotRaisedAsAFailure() = runTest {
        home()
        val racing = object : LocationRepository by store {
            override suspend fun create(name: String): Location {
                store.create(name) // somebody else added it between the check and the write
                return store.create(name)
            }
        }
        val viewModel = LocationsViewModel(racing)
        assertEquals(NameProblem.DUPLICATE, viewModel.add("Garage"))
        assertFalse(viewModel.writes.failed.value)
    }

    @Test
    fun movingALocationUpOrDownReordersTheList() = runTest {
        home()
        val viewModel = LocationsViewModel(store)
        viewModel.add("Garage")
        viewModel.add("Car")
        viewModel.shift(viewModel.locations().last(), -1)
        assertEquals(listOf("Home", "Car", "Garage"), viewModel.names())
        viewModel.shift(viewModel.locations().first(), 1)
        assertEquals(listOf("Car", "Home", "Garage"), viewModel.names())
        // The ends stay where they are, and a move outside the list is ignored.
        viewModel.shift(viewModel.locations().first(), -1)
        viewModel.shift(viewModel.locations().last(), 1)
        viewModel.move(0, 7)
        assertEquals(listOf("Car", "Home", "Garage"), viewModel.names())
    }

    @Test
    fun makingALocationTheDefaultMovesTheMarker() = runTest {
        home()
        val viewModel = LocationsViewModel(store)
        viewModel.add("Garage")
        viewModel.makeDefault(viewModel.locations().last())
        assertEquals(listOf("Garage"), viewModel.locations().filter { it.isDefault }.map { it.name })
    }

    @Test
    fun deletingAnEmptyLocationGoesAtOnceAndUndoBringsItBack() = runTest {
        home()
        val viewModel = LocationsViewModel(store)
        viewModel.add("Garage")
        viewModel.deletions.request(viewModel.locations().last())
        assertEquals(listOf("Home"), viewModel.names())
        assertNull(viewModel.deletions.deletion.value)
        val offer = assertNotNull(viewModel.deletions.undoOffer.value)
        assertEquals("Garage", offer.locationName)
        offer.undo()
        assertEquals(listOf("Home", "Garage"), viewModel.names())
        viewModel.deletions.undoOfferShown()
        assertNull(viewModel.deletions.undoOffer.value)
    }

    @Test
    fun deletingALocationWithBoxesAsksWhereTheyGoThenMovesThem() = runTest {
        val home = home()
        val viewModel = LocationsViewModel(store)
        viewModel.add("Garage")
        val garage = viewModel.locations().last()
        store.insert(box(garage))
        store.insert(box(garage))

        viewModel.deletions.request(garage)
        val request = assertNotNull(viewModel.deletions.deletion.value)
        assertEquals(2, request.boxCount)
        assertEquals(listOf("Home"), request.destinations.map { it.name })
        assertEquals(home.id, request.suggestedDestination)
        assertEquals(listOf("Home", "Garage"), viewModel.names(), "nothing changes until the user confirms")

        viewModel.deletions.confirm(request, home.id)
        assertEquals(listOf("Home"), viewModel.names())
        assertNull(viewModel.deletions.deletion.value)
        assertNull(viewModel.deletions.undoOffer.value, "a deletion that moved boxes is confirmed, not undone")
        assertEquals(setOf(home.id), store.activeBoxes().map { it.locationId }.toSet())
        assertEquals(2, store.activeBoxes().size)
    }

    @Test
    fun theDefaultLocationIsNeverDeleted() = runTest {
        val home = home()
        val viewModel = LocationsViewModel(store)
        viewModel.deletions.request(home)
        assertNull(viewModel.deletions.deletion.value)
        assertNull(viewModel.deletions.undoOffer.value)
        assertEquals(listOf("Home"), viewModel.names())
    }

    @Test
    fun cancellingTheDeletionChangesNothing() = runTest {
        home()
        val viewModel = LocationsViewModel(store)
        viewModel.add("Garage")
        val garage = viewModel.locations().last()
        store.insert(box(garage))
        viewModel.deletions.request(garage)
        assertNotNull(viewModel.deletions.deletion.value)
        viewModel.deletions.cancel()
        assertNull(viewModel.deletions.deletion.value)
        assertEquals(listOf("Home", "Garage"), viewModel.names())
        assertEquals(1, store.cabinetBoxCount(garage.id))
    }

    @Test
    fun aFailedWriteRaisesTheAlertAndKeepsTheList() = runTest {
        home()
        val failing = object : LocationRepository by store {
            override suspend fun create(name: String): Location = error("disk full")
        }
        val viewModel = LocationsViewModel(failing)
        assertNull(viewModel.add("Garage"))
        assertTrue(viewModel.writes.failed.value)
        assertEquals(listOf("Home"), viewModel.names())
        viewModel.writes.acknowledge()
        assertFalse(viewModel.writes.failed.value)
    }

    @Test
    fun aFailedBoxCountRaisesTheAlertInsteadOfDeleting() = runTest {
        home()
        val failing = object : LocationRepository by store {
            override suspend fun cabinetBoxCount(id: UUID): Int = error("unreadable")
        }
        val viewModel = LocationsViewModel(failing)
        viewModel.add("Garage")
        viewModel.deletions.request(viewModel.locations().last())
        assertTrue(viewModel.writes.failed.value)
        assertEquals(listOf("Home", "Garage"), viewModel.names())
    }

    @Test
    fun aListThatCannotBeReadShowsTheFailedStateAndRetryRecovers() = runTest {
        home()
        var broken = true
        val flaky = object : LocationRepository by store {
            override fun observeAll(): Flow<List<Location>> =
                if (broken) flow { error("unreadable") } else store.observeAll()
        }
        val viewModel = LocationsViewModel(flaky)
        assertIs<LocationsUiState.Failed>(viewModel.state.value)
        broken = false
        viewModel.observe()
        assertEquals(listOf("Home"), viewModel.names())
    }
}
