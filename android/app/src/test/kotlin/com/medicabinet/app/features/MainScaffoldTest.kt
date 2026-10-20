package com.medicabinet.app.features

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.medicabinet.app.app.AppTab
import com.medicabinet.app.app.TabRouter
import com.medicabinet.app.features.cabinet.CabinetTestKit
import com.medicabinet.app.features.scan.testEnvironment
import com.medicabinet.app.features.scan.testScanServices
import com.medicabinet.app.reminders.DeliveryKit
import com.medicabinet.app.reminders.ReminderServices
import com.medicabinet.app.reminders.ReminderTestKit
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.RemovalReason
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    /** A bottom-bar item (the Cabinet screen has a title with the same word). */
    private fun tab(name: String) = compose.onNode(hasText(name) and isSelectable())

    @Test
    fun fourDestinationsAreShown() {
        show()
        listOf("Scan", "Cabinet", "Expiring", "Settings").forEach { tab(it).assertIsDisplayed() }
    }

    // Issue #53: the app opens on the Cabinet, and the camera screen is not even composed until Scan is opened.
    @Test
    fun theCabinetIsTheStartDestinationAndTheScannerWaitsForItsTab() {
        show()
        waitForText("The cabinet is empty")
        tab("Cabinet").assertIsSelected()
        assertEquals(0, compose.onAllNodesWithTag("scan.permission.allow").fetchSemanticsNodes().size)
        compose.onNodeWithText("Scan").performClick()
        compose.onNodeWithText("Allow the camera").assertIsDisplayed()
        tab("Scan").assertIsSelected()
    }

    @Test
    fun theTabsExplainThemselvesWhenThereIsNothingToShow() {
        show()
        waitForText("The cabinet is empty")
        compose.onNodeWithText("Expiring").performClick()
        waitForText("All clear")
        compose.onNodeWithText("Nothing expires in the next 60 days.").assertExists()
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
        // Still on the start tab (Cabinet): the badge is live, and says two boxes (the unknown one is not counted).
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

    private fun services(kit: ReminderTestKit) = ReminderServices(
        kit.access,
        kit.scheduling,
        DeliveryKit(Instant.parse("2027-01-15T12:00:00Z")).delivery
    )

    @Test
    fun comingToTheForegroundArmsTheDigestAlarmAgain() {
        val kit = ReminderTestKit()
        compose.setContent {
            MediCabinetTheme {
                MainScaffold(testEnvironment(), scanServices = testScanServices(), reminderServices = services(kit))
            }
        }
        compose.waitUntil(WAIT_MS) { kit.scheduling.arms >= 1 }
    }

    @Test
    fun theExpiringTabOffersTheMonthlyReminderAndNotNowRemembersIt() {
        val kit = ReminderTestKit()
        val environment = testEnvironment()
        compose.setContent {
            MediCabinetTheme {
                MainScaffold(
                    environment,
                    scanServices = testScanServices(),
                    reminderServices = services(kit)
                )
            }
        }
        compose.onNodeWithText("Expiring").performClick()
        waitForText("All clear")
        compose.onNodeWithTag("reminder.card").assertIsDisplayed()
        compose.onNodeWithTag("reminder.card.notNow").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("reminder.card").fetchSemanticsNodes().size)
        assertTrue(environment.expiryPreferences.reminderCardDismissed)
    }

    @Test
    fun settingsOfferTheMonthlyReminderSwitchOffByDefault() {
        show()
        compose.onNodeWithText("Settings").performClick()
        // The Products you named row pushed the reminder below the fold of the small test screen: Settings scrolls.
        compose.onNodeWithTag("settings.reminder.switch").performScrollTo().assertIsDisplayed().assertIsOff()
    }

    @Test
    fun aTabRequestFromOutsideSwitchesToTheExpiringTab() {
        val router = TabRouter()
        compose.setContent {
            MediCabinetTheme { MainScaffold(testEnvironment(), scanServices = testScanServices(), tabRouter = router) }
        }
        waitForText("The cabinet is empty")
        router.select(AppTab.EXPIRING)
        waitForText("All clear")
        assertNull(router.requested.value)
    }

    @Test
    fun aTabRequestMadeBeforeTheScreenExistsWinsOverTheStartTab() {
        val router = TabRouter()
        router.select(AppTab.EXPIRING)
        compose.setContent {
            MediCabinetTheme { MainScaffold(testEnvironment(), scanServices = testScanServices(), tabRouter = router) }
        }
        waitForText("All clear")
        tab("Expiring").assertIsSelected()
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
            tab(it).assertIsDisplayed().assertHasClickAction()
        }
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
            compose.onNode(hasText(it) and isSelectable()).assertIsDisplayed()
        }
        compose.onNodeWithText("Scanner").performClick()
        compose.onNodeWithTag("scan.permission.allow").assertIsDisplayed()
    }
}
