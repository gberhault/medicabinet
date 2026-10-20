package com.medicabinet.app.features.settings.locations

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.app.features.common.UndoController
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.Box
import com.medicabinet.core.FixedClock
import com.medicabinet.core.Location
import com.medicabinet.core.LocationRepository
import com.medicabinet.core.ProductRef
import com.medicabinet.core.testing.InMemoryCabinetStore
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class LocationsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val clock = FixedClock(Instant.ofEpochSecond(1_800_000_000))
    private val store = InMemoryCabinetStore(clock)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Composable
    private fun Host(viewModel: LocationsViewModel) {
        val snackbars = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val controller = remember { UndoController(snackbars, scope) }
        MediCabinetTheme {
            CompositionLocalProvider(LocalUndoController provides controller) {
                Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
                    LocationsScreen(viewModel, onBack = {}, modifier = Modifier.padding(padding))
                }
            }
        }
    }

    private fun show(repository: LocationRepository = store): LocationsViewModel {
        runBlocking { store.ensureDefault("Home") }
        val viewModel = LocationsViewModel(repository)
        compose.setContent { Host(viewModel) }
        return viewModel
    }

    private fun addThroughTheDialog(name: String) {
        compose.onNodeWithContentDescription("Add a location").performClick()
        compose.onNodeWithTag("locations.name.field").performTextInput(name)
        compose.onNodeWithTag("locations.name.save").performClick()
    }

    private fun box(location: Location) = Box(
        product = ProductRef("03400930000014", "Doliprane"),
        locationId = location.id,
        serial = UUID.randomUUID().toString().take(12),
        createdAt = clock.now()
    )

    @Test
    fun theDefaultLocationIsMarkedWithTextAndAnIcon() {
        show()
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Default").assertIsDisplayed()
    }

    @Test
    fun addingALocationShowsItInTheList() {
        show()
        addThroughTheDialog("Garage")
        compose.onNodeWithText("Garage").assertIsDisplayed()
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun aRefusedNameExplainsWhyAndKeepsTheDialogOpen() {
        show()
        addThroughTheDialog("hôme")
        compose.onNodeWithText("A location already has this name.").assertIsDisplayed()
        compose.onNodeWithTag("locations.name.field").assertIsDisplayed()
    }

    @Test
    fun renamingThroughTheRowMenu() {
        show()
        addThroughTheDialog("Garage")
        compose.onAllNodesWithTag("locations.menu")[1].performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag("locations.name.field").performTextInput(" 2")
        compose.onNodeWithTag("locations.name.save").performClick()
        compose.onNodeWithText("Garage 2").assertIsDisplayed()
    }

    @Test
    fun deletingAnEmptyLocationOffersAnUndoThatBringsItBack() {
        show()
        addThroughTheDialog("Garage")
        compose.onAllNodesWithTag("locations.menu")[1].performClick()
        compose.onNodeWithText("Delete").performClick()

        compose.onNodeWithText("Location deleted: Garage").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertIsDisplayed().assertHasClickAction()
        assertEquals(0, compose.onAllNodes(hasText("Garage", substring = false)).fetchSemanticsNodes().size)

        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Garage").assertIsDisplayed()
    }

    @Test
    fun theDefaultLocationOffersNoDeleteAndTheScreenSaysWhy() {
        show()
        compose.onAllNodesWithTag("locations.menu")[0].performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Delete")).fetchSemanticsNodes().size)
        assertEquals(
            0,
            compose.onAllNodes(hasText("Make default")).fetchSemanticsNodes().size
        )
        compose.onNodeWithText("To delete the default location, choose another default location first.")
            .assertExists()
    }

    @Test
    fun deletingALocationWithBoxesAsksWhereTheyGoThenMovesThem() {
        val viewModel = show()
        addThroughTheDialog("Garage")
        val garage = (viewModel.state.value as LocationsUiState.Loaded).locations.last()
        runBlocking {
            store.insert(box(garage))
            store.insert(box(garage))
        }
        compose.onAllNodesWithTag("locations.menu")[1].performClick()
        compose.onNodeWithText("Delete").performClick()

        compose.onNodeWithText("Delete this location?").assertIsDisplayed()
        compose.onNodeWithText("This location holds 2 boxes. Where should they go?").assertIsDisplayed()
        compose.onNodeWithText("Move and delete").performClick()

        assertEquals(listOf("Home"), (viewModel.state.value as LocationsUiState.Loaded).locations.map { it.name })
        assertEquals(2, runBlocking { store.activeBoxes() }.size)
    }

    @Test
    fun draggingTheHandleMovesTheRowAndTheCustomActionsMoveItToo() {
        val viewModel = show()
        addThroughTheDialog("Garage")
        addThroughTheDialog("Car")
        fun names() = (viewModel.state.value as LocationsUiState.Loaded).locations.map { it.name }
        assertEquals(listOf("Home", "Garage", "Car"), names())
        compose.onAllNodesWithTag("locations.handle", useUnmergedTree = true)[2].performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, -2_000f))
            up()
        }
        assertEquals(listOf("Car", "Home", "Garage"), names())

        val config = compose.onNodeWithText("Garage").fetchSemanticsNode().config
        val actions = assertNotNull(config.getOrNull(SemanticsActions.CustomActions))
        assertEquals(listOf("Move up", "Move down"), actions.map { it.label })
        actions.first { it.label == "Move up" }.action()
        assertEquals(listOf("Car", "Garage", "Home"), names())
    }

    @Test
    fun aListThatCannotBeReadShowsAnErrorWithRetry() {
        runBlocking { store.ensureDefault("Home") }
        var broken = true
        val flaky = object : LocationRepository by store {
            override fun observeAll(): Flow<List<Location>> =
                if (broken) flow { error("unreadable") } else store.observeAll()
        }
        show(flaky)
        compose.onNodeWithText("Locations unavailable").assertIsDisplayed()
        broken = false
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun aFailedWriteShowsAnAlertAndChangesNothing() {
        val failing = object : LocationRepository by store {
            override suspend fun create(name: String): Location = error("disk full")
        }
        show(failing)
        addThroughTheDialog("Garage")
        compose.onNodeWithText("Couldn't save the change").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        assertEquals(0, compose.onAllNodes(hasText("Garage")).fetchSemanticsNodes().size)
    }

    @Test
    fun theScreenStaysUsableAtTwiceTheFontSize() {
        RuntimeEnvironment.setFontScale(2f)
        show()
        compose.onNodeWithContentDescription("Add a location").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Home").assertIsDisplayed()
        addThroughTheDialog("Garage")
        compose.onNodeWithText("Garage").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr")
class LocationsScreenFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theScreenSpeaksFrenchWithPluralBoxCounts() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val store = InMemoryCabinetStore(FixedClock(Instant.ofEpochSecond(1_800_000_000)))
            val viewModel = runBlocking {
                store.ensureDefault("Armoire")
                val garage = store.create("Garage")
                store.insert(
                    Box(
                        product = ProductRef("03400930000014", "Doliprane"),
                        locationId = garage.id,
                        createdAt = Instant.ofEpochSecond(1)
                    )
                )
                LocationsViewModel(store)
            }
            compose.setContent { MediCabinetTheme { LocationsScreen(viewModel, onBack = {}) } }
            compose.onNodeWithText("Lieux").assertIsDisplayed()
            compose.onNodeWithText("Par défaut").assertIsDisplayed()
            compose.onAllNodesWithTag("locations.menu")[1].performClick()
            compose.onNodeWithText("Supprimer").performClick()
            compose.onNodeWithText("Ce lieu contient 1 boîte. Où la déplacer ?").assertIsDisplayed()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
