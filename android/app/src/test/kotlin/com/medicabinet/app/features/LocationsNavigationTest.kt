package com.medicabinet.app.features

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.features.scan.testScanServices
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.FixedClock
import com.medicabinet.core.testing.InMemoryCabinetStore
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Locations screen reached the way a user reaches it: Settings tab → Locations, inside the main scaffold. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class LocationsNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private fun open(): InMemoryCabinetStore {
        val clock = FixedClock(Instant.ofEpochSecond(1_800_000_000))
        val store = InMemoryCabinetStore(clock)
        runBlocking { store.ensureDefault("Medicine cabinet") }
        compose.setContent {
            MediCabinetTheme {
                MainScaffold(AppEnvironment(store, store, clock), scanServices = testScanServices())
            }
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings.locations").performClick()
        return store
    }

    @Test
    fun settingsLeadToTheLocationsScreen() {
        open()
        compose.onNodeWithText("Medicine cabinet").assertIsDisplayed()
        compose.onNodeWithText("Default").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add a location").assertIsDisplayed()
    }

    @Test
    fun theUndoMessageShowsAboveTheNavigationBarAndUndoBringsTheLocationBack() {
        open()
        compose.onNodeWithContentDescription("Add a location").performClick()
        compose.onNodeWithTag("locations.name.field").performTextInput("Garage")
        compose.onNodeWithTag("locations.name.save").performClick()
        compose.onAllNodesWithTag("locations.menu")[1].performClick()
        compose.onNodeWithText("Delete").performClick()

        compose.onNodeWithText("Location deleted: Garage").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Garage").assertIsDisplayed()
    }

    @Test
    fun theBackArrowReturnsToSettings() {
        open()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("settings.locations").assertIsDisplayed()
    }
}
