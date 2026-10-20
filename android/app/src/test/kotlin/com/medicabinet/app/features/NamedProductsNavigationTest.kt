package com.medicabinet.app.features

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.features.scan.testScanServices
import com.medicabinet.app.features.settings.namedproducts.NamedProductsKit
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.InMemoryReferenceData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The list reached the way a user reaches it: Settings tab → Products you named, inside the main scaffold. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class NamedProductsNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = NamedProductsKit()

    private fun open() {
        kit.name("03401900000003", "Crème solaire", boxes = 1)
        compose.setContent {
            MediCabinetTheme {
                MainScaffold(
                    AppEnvironment(
                        kit.store,
                        kit.store,
                        kit.store.named,
                        kit.clock,
                        InMemoryReferenceData(status = ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING))
                    ),
                    scanServices = testScanServices()
                )
            }
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings.namedProducts").performClick()
    }

    @Test
    fun settingsLeadToTheListOfNamedProducts() {
        open()
        compose.onNodeWithText("Crème solaire").assertIsDisplayed()
        compose.onNodeWithText("1 box in the cabinet").assertIsDisplayed()
        compose.onNodeWithTag("namedProducts.list").assertIsDisplayed()
    }

    @Test
    fun theBackArrowReturnsToSettings() {
        open()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("settings.namedProducts").assertIsDisplayed()
    }

    @Test
    fun theUndoMessageShowsAboveTheNavigationBarAndUndoBringsTheProductBack() {
        open()
        compose.onAllNodesWithTag("namedProducts.menu")[0].performClick()
        compose.onNodeWithText("Forget").performClick()
        compose.onNodeWithTag("namedProducts.forget.confirm").performClick()

        compose.onNodeWithText("“Crème solaire” forgotten").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Crème solaire").assertIsDisplayed()
    }

    @Test
    fun aRenameShowsInTheCabinetTab() {
        open()
        compose.onNodeWithText("Crème solaire").performClick()
        compose.onNodeWithTag("namedProducts.edit.name").performTextClearance()
        compose.onNodeWithTag("namedProducts.edit.name").performTextInput("Crème solaire SPF50")
        compose.onNodeWithTag("namedProducts.edit.save").performClick()
        compose.onNodeWithText("Crème solaire SPF50").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Cabinet").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag("cabinet.pill")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cabinet.pill").assertContentDescriptionContains("SPF50", substring = true)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
