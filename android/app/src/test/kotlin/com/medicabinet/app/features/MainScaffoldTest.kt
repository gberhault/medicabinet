package com.medicabinet.app.features

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.medicabinet.app.app.AppTab
import com.medicabinet.app.app.TabRouter
import com.medicabinet.app.features.cabinet.CabinetTestKit
import com.medicabinet.app.features.scan.testEnvironment
import com.medicabinet.app.features.scan.testScanServices
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.RemovalReason
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class MainScaffoldTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(dark: Boolean = false) = compose.setContent {
        MediCabinetTheme(darkTheme = dark) { MainScaffold(testEnvironment(), scanServices = testScanServices()) }
    }

    @Test
    fun fourDestinationsAreShown() {
        show()
        listOf("Scan", "Cabinet", "Expiring", "Settings").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun scanIsTheStartDestinationTheCabinetShowsItsEmptyStateAndUnbuiltFeaturesExplainThemselves() {
        show()
        compose.onNodeWithText("Allow the camera").assertIsDisplayed()
        compose.onNodeWithText("Cabinet").performClick()
        waitForText("The cabinet is empty")
        compose.onNodeWithText("Expiring").performClick()
        waitForText("All clear")
        compose.onNodeWithText("Nothing expires in the next 60 days.").assertIsDisplayed()
    }

    @Test
    fun theExpiringTabBadgeCountsBoxesNeedingAttentionOnEveryTabAndIsHiddenAtZero() {
        val kit = CabinetTestKit()
        runBlocking {
            kit.open()
            kit.box("Smecta", expiry = CabinetTestKit.EXPIRED)
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON)
            kit.box("Pansements")
        }
        compose.setContent {
            MediCabinetTheme { MainScaffold(kit.environment, scanServices = testScanServices()) }
        }
        // Still on the Scan tab: the badge is live, and says two boxes (the unknown one is not counted).
        waitForTabDescription("Expiring, 2 boxes need attention", present = true)
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        runBlocking {
            kit.store.box(kit.store.activeBoxes().first { it.product.name == "Smecta" }.id)?.let {
                kit.store.markRemoved(it.id, RemovalReason.THROWN_AWAY, kit.clock.now())
            }
        }
        waitForTabDescription("Expiring, 1 box needs attention", present = true)
        runBlocking {
            kit.store.activeBoxes().filter { it.product.name == "Doliprane" }.forEach {
                kit.store.markRemoved(it.id, RemovalReason.USED_UP, kit.clock.now())
            }
        }
        waitForTabDescription("Expiring, 1 box needs attention", present = false)
        // Hidden at zero: no badge number and no count in the tab's description.
        assertEquals(0, compose.onAllNodesWithText("1", useUnmergedTree = true).fetchSemanticsNodes().size)
        val described = compose.onAllNodesWithContentDescription("need attention", substring = true)
        assertEquals(0, described.fetchSemanticsNodes().size)
    }

    @Test
    fun aTabRequestFromOutsideSwitchesToTheExpiringTab() {
        val router = TabRouter()
        compose.setContent {
            MediCabinetTheme { MainScaffold(testEnvironment(), scanServices = testScanServices(), tabRouter = router) }
        }
        compose.onNodeWithText("Allow the camera").assertIsDisplayed()
        router.select(AppTab.EXPIRING)
        waitForText("All clear")
        assertNull(router.requested.value)
    }

    @Test
    fun settingsOfferTheExpiringSoonWindow() {
        show()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings.expiryWindow.60").assertIsDisplayed()
        compose.onNodeWithTag("settings.expiryWindow.90").performClick()
        compose.onNodeWithTag("settings.expiryWindow.90").assertIsSelected()
    }

    @Test
    fun destinationsStayUsableAtTwiceTheFontSizeInTheDarkTheme() {
        RuntimeEnvironment.setFontScale(2f)
        show(dark = true)
        listOf("Scan", "Cabinet", "Expiring", "Settings").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithText("Cabinet").performClick()
        waitForText("The cabinet is empty")
    }

    private fun waitForTabDescription(description: String, present: Boolean) = compose.waitUntil(WAIT_MS) {
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() == present
    }

    private fun waitForText(text: String) = compose.waitUntil(WAIT_MS) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr")
class MainScaffoldFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSameDestinationsAreShownInFrench() {
        compose.setContent { MediCabinetTheme { MainScaffold(testEnvironment(), scanServices = testScanServices()) } }
        listOf("Scanner", "Armoire", "Péremptions", "Réglages").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("scan.permission.allow").assertIsDisplayed()
    }
}
