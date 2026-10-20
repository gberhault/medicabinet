package com.medicabinet.app.features.expiring

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.features.cabinet.CabinetTestKit
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.app.features.common.UndoController
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.Box
import com.medicabinet.core.BoxRepository
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.expiry.ExpiryWindow
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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

private const val WAIT_MS = 10_000L

/** Shows the Expiring screen with a snackbar host, as `MainScaffold` does, for Undo. */
internal fun androidx.compose.ui.test.junit4.ComposeContentTestRule.showExpiring(
    model: ExpiringViewModel,
    dark: Boolean = false,
    card: @androidx.compose.runtime.Composable () -> Unit = {}
) = setContent {
    MediCabinetTheme(darkTheme = dark) {
        val snackbars = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val undo = remember { UndoController(snackbars, scope) }
        CompositionLocalProvider(LocalUndoController provides undo) {
            Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
                ExpiringScreen(model, Modifier.padding(padding), card)
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w360dp-h2400dp")
class ExpiringScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = CabinetTestKit()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun show(environment: AppEnvironment = kit.environment, dark: Boolean = false) =
        compose.showExpiring(ExpiringViewModel(environment, UnconfinedTestDispatcher()), dark)

    private fun seed(): Map<String, Box> = runBlocking {
        kit.open()
        val kitchen = kit.store.create("Kitchen")
        mapOf(
            "smecta" to kit.box("Smecta", expiry = CabinetTestKit.EXPIRED),
            "doliprane" to kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.SOON, kitchen),
            "spasfon" to kit.box("Spasfon", expiry = ExpiryDate.of(2027, 1, 15)),
            "fine" to kit.box("Efferalgan", CabinetTestKit.EFFERALGAN, CabinetTestKit.FINE),
            "pansements" to kit.box("Pansements")
        )
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun threeSectionsInOrderWithRowsInTheirSections() {
        seed()
        show()
        compose.onNodeWithTag("expiring.section.expired").assertIsDisplayed()
        compose.onNodeWithTag("expiring.section.expiringSoon").assertIsDisplayed()
        compose.onNodeWithTag("expiring.section.unknown").assertIsDisplayed()
        assertTrue(top("expiring.section.expired") < top("expiring.section.expiringSoon"))
        assertTrue(top("expiring.section.expiringSoon") < top("expiring.section.unknown"))
        compose.onNodeWithText("Smecta").assertIsDisplayed()
        compose.onNodeWithText("Doliprane").assertIsDisplayed()
        compose.onNodeWithText("Pansements").assertIsDisplayed()
    }

    @Test
    fun anOkBoxIsNeverListed() {
        seed()
        show()
        assertEquals(0, compose.onAllNodesWithText("Efferalgan").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("OK").fetchSemanticsNodes().size)
    }

    @Test
    fun aSectionWithNoBoxIsHidden() {
        runBlocking {
            kit.open()
            kit.box("Smecta", expiry = CabinetTestKit.EXPIRED)
        }
        show()
        compose.onNodeWithTag("expiring.section.expired").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("expiring.section.expiringSoon"))
        assertEquals(0, compose.onAllNodesWithTag("expiring.section.unknown"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTag(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun eachRowShowsNameLocationExpiryAndTheStatusAsTextAndIcon() {
        seed()
        show()
        // Month precision: the month and year, never an invented day.
        compose.onNodeWithText("Expires: December 2026").assertIsDisplayed()
        compose.onNodeWithText("Expires: February 10, 2027").assertIsDisplayed()
        compose.onNodeWithText("Kitchen").assertIsDisplayed()
        assertEquals(3, compose.onAllNodesWithText("Home").fetchSemanticsNodes().size)
        // The status is words (the section header says the same words, so count the labels on rows too).
        assertTrue(compose.onAllNodesWithText("Expired").fetchSemanticsNodes().size >= 2)
        compose.onNodeWithText("Expires today").assertIsDisplayed()
    }

    @Test
    fun anUnknownExpiryIsSaidOnceAndNeverShownAsADate() {
        seed()
        show()
        // Header and the row's status label; no "Expires:" line for the unknown box.
        assertEquals(2, compose.onAllNodesWithText("Expiry unknown").fetchSemanticsNodes().size)
        assertEquals(3, compose.onAllNodesWithText("Expires:", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aRowIsSpokenAsNameStatusExpiryAndLocation() {
        seed()
        show()
        compose.onNodeWithContentDescription("Doliprane, Expiring soon, Expires: February 10, 2027, Kitchen")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Spasfon, Expires today, Expires: January 15, 2027, Home")
            .assertIsDisplayed()
    }

    @Test
    fun rowsOpenNothing() {
        seed()
        show()
        compose.onNodeWithText("Smecta").assertHasNoClickAction()
    }

    @Test
    fun nothingToLookAtNamesTheWindow() {
        runBlocking {
            kit.open()
            kit.box("Efferalgan", CabinetTestKit.EFFERALGAN, CabinetTestKit.FINE)
        }
        kit.environment.expiryPreferences.window = ExpiryWindow.DAYS_90
        show()
        compose.onNodeWithText("All clear").assertIsDisplayed()
        compose.onNodeWithText("Nothing expires in the next 90 days.").assertIsDisplayed()
    }

    @Test
    fun anEmptyCabinetIsAllClearToo() {
        runBlocking { kit.open() }
        show()
        compose.onNodeWithText("Nothing expires in the next 60 days.").assertIsDisplayed()
    }

    @Test
    fun whileTheCabinetLoadsALoadingMessageIsShown() {
        runBlocking { kit.open() }
        val never = object : BoxRepository by kit.store {
            override fun observeCabinet(): Flow<List<Box>> = flow { awaitCancellation() }
        }
        show(AppEnvironment(kit.store, never, kit.clock, kit.environment.reference, kit.environment.zone))
        compose.onNodeWithText("Loading expiry dates…").assertIsDisplayed()
    }

    @Test
    fun anUnreadableCabinetShowsAnErrorWithRetry() {
        runBlocking { kit.open() }
        show(kit.failingEnvironment())
        compose.onNodeWithText("Couldn’t load expiry dates").assertIsDisplayed()
        compose.onNodeWithTag("expiring.retry").performClick()
        compose.onNodeWithText("Couldn’t load expiry dates").assertIsDisplayed()
    }

    @Test
    fun theListFollowsTheCabinetAndTheWindowWithoutARefresh() {
        seed()
        show()
        runBlocking { kit.box("Spasfon 2", expiry = CabinetTestKit.EXPIRED) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Spasfon 2").fetchSemanticsNodes().isNotEmpty() }
        // 45 days left: soon in a 60-day window, gone in a 30-day one.
        runBlocking { kit.box("Cetirizine", expiry = ExpiryDate.of(2027, 3, 1)) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Cetirizine").fetchSemanticsNodes().isNotEmpty() }
        kit.environment.expiryPreferences.window = ExpiryWindow.DAYS_30
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Cetirizine").fetchSemanticsNodes().isEmpty() }
    }

    private fun throwAwayAction(name: String) {
        val config = compose.onNode(hasText(name)).fetchSemanticsNode().config
        val action = config[SemanticsActions.CustomActions].first { it.label == "Thrown away" }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
    }

    private fun storedRemoval(box: Box) = runBlocking { kit.store.box(box.id)?.removal }

    @Test
    fun throwAwayByAccessibilityActionRemovesTheBoxAndOffersUndo() {
        val boxes = seed()
        show()
        throwAwayAction("Smecta")
        assertEquals(RemovalReason.THROWN_AWAY, assertNotNull(storedRemoval(boxes.getValue("smecta"))).reason)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("Smecta thrown away").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, compose.onAllNodesWithText("Smecta").fetchSemanticsNodes().size)
    }

    @Test
    fun undoBringsTheBoxBack() {
        val boxes = seed()
        show()
        throwAwayAction("Smecta")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(WAIT_MS) { storedRemoval(boxes.getValue("smecta")) == null }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Smecta").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun swipingARowThrowsItAway() {
        val boxes = seed()
        show()
        compose.onNodeWithText("Smecta").performTouchInput { swipeLeft() }
        compose.waitUntil(WAIT_MS) { storedRemoval(boxes.getValue("smecta")) != null }
        assertEquals(RemovalReason.THROWN_AWAY, assertNotNull(storedRemoval(boxes.getValue("smecta"))).reason)
    }

    @Test
    fun aFailedWriteSaysSoAndLeavesTheBoxListed() {
        val boxes = seed()
        show(kit.readOnlyEnvironment())
        throwAwayAction("Smecta")
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("Couldn’t throw this box away").fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(storedRemoval(boxes.getValue("smecta")))
        compose.onNodeWithText("Smecta").assertIsDisplayed()
    }

    @Test
    fun theReminderCardIsShownAboveTheListAndInTheEmptyState() {
        seed()
        compose.showExpiring(ExpiringViewModel(kit.environment, UnconfinedTestDispatcher())) {
            androidx.compose.material3.Text("card-slot")
        }
        compose.onNodeWithText("card-slot").assertIsDisplayed()
        val cardTop = compose.onNodeWithText("card-slot").fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("expiring.section.expired") > cardTop)
    }

    @Test
    fun theReminderCardIsAlsoShownWhenNothingNeedsAttention() {
        runBlocking { kit.open() }
        compose.showExpiring(ExpiringViewModel(kit.environment, UnconfinedTestDispatcher())) {
            androidx.compose.material3.Text("card-slot")
        }
        compose.onNodeWithText("card-slot").assertIsDisplayed()
        compose.onNodeWithText("All clear").assertIsDisplayed()
    }

    @Test
    fun everythingStaysReachableAtTwiceTheFontSizeInTheDarkTheme() {
        seed()
        RuntimeEnvironment.setFontScale(2f)
        show(dark = true)
        compose.onNodeWithTag("expiring.section.expired").assertIsDisplayed()
        compose.onNodeWithTag("expiring.list").performScrollToNode(hasText("Pansements"))
        compose.onNodeWithText("Pansements").assertIsDisplayed()
        // Thrown away stays reachable without a swipe.
        assertTrue(
            compose.onNode(hasText("Pansements")).fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .any { it.label == "Thrown away" }
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr-w360dp-h2400dp")
class ExpiringScreenFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = CabinetTestKit()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun theSameScreenSpeaksFrenchWithMonthAndYear() {
        runBlocking {
            kit.open()
            kit.box("Smecta", expiry = CabinetTestKit.EXPIRED)
            kit.box("Spasfon", expiry = ExpiryDate.of(2027, 1, 15))
            kit.box("Pansements")
        }
        compose.showExpiring(ExpiringViewModel(kit.environment, UnconfinedTestDispatcher()))
        compose.onNodeWithText("Péremption : décembre 2026").assertIsDisplayed()
        compose.onNodeWithText("Périme aujourd’hui").assertIsDisplayed()
        compose.onNodeWithTag("expiring.section.unknown").assertIsDisplayed()
    }

    @Test
    fun theEmptyStateNamesTheWindowInFrench() {
        runBlocking { kit.open() }
        compose.showExpiring(ExpiringViewModel(kit.environment, UnconfinedTestDispatcher()))
        compose.onNodeWithText("Tout est à jour").assertIsDisplayed()
        compose.onNodeWithText("Rien ne périme dans les 60 prochains jours.").assertIsDisplayed()
    }
}
