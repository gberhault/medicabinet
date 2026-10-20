package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.app.features.common.UndoController
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.reference.ReferenceProduct
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@Config(qualifiers = "en-w360dp-h2400dp")
class BoxDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = CabinetTestKit(
        mapOf("3400930000014" to ReferenceProduct("DOLIPRANE 1000 mg", CategoryID.PAIN_FEVER))
    )
    private var backs = 0

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Composable
    private fun Host(viewModel: BoxDetailViewModel, environment: AppEnvironment) {
        val snackbars = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val controller = remember { UndoController(snackbars, scope) }
        MediCabinetTheme {
            CompositionLocalProvider(LocalUndoController provides controller) {
                Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
                    BoxDetailScreen(viewModel, environment.zone, onBack = {
                        backs++
                    }, modifier = Modifier.padding(padding))
                }
            }
        }
    }

    private fun show(box: Box, environment: AppEnvironment = kit.environment): BoxDetailViewModel {
        val viewModel = BoxDetailViewModel(box.id, environment, UnconfinedTestDispatcher())
        compose.setContent { Host(viewModel, environment) }
        return viewModel
    }

    private fun stored(box: Box): Box? = runBlocking { kit.store.box(box.id) }

    private fun doliprane(
        expiry: com.medicabinet.core.ExpiryDate? = CabinetTestKit.SOON,
        gtin: String? = CabinetTestKit.DOLIPRANE
    ): Box = runBlocking {
        kit.open()
        kit.box(
            "Doliprane 1000",
            gtin,
            expiry,
            category = CategoryID.PAIN_FEVER,
            lot = "LOT42",
            serial = "SERIAL-SECRET-1"
        )
    }

    @Test
    fun showsWhatTheBoxIsWhereItIsAndWhetherItIsStillGood() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.name").assertIsDisplayed()
        compose.onNodeWithText("Doliprane 1000").assertIsDisplayed()
        compose.onNodeWithText("3400930000014").assertIsDisplayed()
        compose.onNodeWithText("Pain and fever").assertIsDisplayed()
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Expires February 10, 2027").assertIsDisplayed()
        compose.onNodeWithText("Expiring soon").assertIsDisplayed()
        compose.onNodeWithText("LOT42").assertIsDisplayed()
        compose.onNodeWithText("Jan 15, 2027").assertIsDisplayed()
        compose.onNodeWithText(
            "Categories are for tidying the cabinet, not for choosing a treatment."
        ).assertIsDisplayed()
    }

    @Test
    fun theSerialNumberIsNeverShown() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.name").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("SERIAL-SECRET-1", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun aBoxWithoutACodeSaysSoAndDoesNotShowALotRow() {
        runBlocking { kit.open() }
        val box = runBlocking { kit.box("Pansements", expiry = CabinetTestKit.FINE) }
        show(box)
        compose.onNodeWithText("No code").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Lot")).fetchSemanticsNodes().size)
    }

    @Test
    fun anUnknownExpiryIsSaidOnceAndNeverShownAsOk() {
        val box = doliprane(expiry = null)
        show(box)
        compose.onNodeWithText("Expiry unknown").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("OK")).fetchSemanticsNodes().size)
    }

    @Test
    fun aCategoryTheUserChoseIsMarked() {
        val box = runBlocking {
            kit.open()
            kit.box("Doliprane", expiry = CabinetTestKit.FINE, category = CategoryID.EYES, overridden = true)
        }
        show(box)
        compose.onNodeWithText("Set by you").assertIsDisplayed()
    }

    @Test
    fun theOpenedSwitchSavesAtOnce() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.opened").assertHasClickAction().performClick()
        assertEquals(true, stored(box)?.isOpened)
        compose.onNodeWithTag("box.opened").performClick()
        assertEquals(false, stored(box)?.isOpened)
    }

    @Test
    fun editingTheNameAndSavingStoresIt() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.edit").performClick()
        compose.onNodeWithTag("box.edit.name").performTextClearance()
        compose.onNodeWithTag("box.edit.name").performTextInput("Doliprane 500")
        compose.onNodeWithTag("box.edit.save").performClick()
        assertEquals("Doliprane 500", stored(box)?.product?.name)
        compose.onNodeWithText("Doliprane 500").assertIsDisplayed()
    }

    @Test
    fun cancellingTheEditChangesNothing() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.edit").performClick()
        compose.onNodeWithTag("box.edit.name").performTextReplacement("Other")
        compose.onNodeWithTag("box.edit.cancel").performClick()
        assertEquals("Doliprane 1000", stored(box)?.product?.name)
        compose.onNodeWithText("Doliprane 1000").assertIsDisplayed()
    }

    @Test
    fun anEmptyNameIsExplainedAndCannotBeSaved() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.edit").performClick()
        compose.onNodeWithTag("box.edit.name").performTextClearance()
        compose.onNodeWithText("The name can't be empty.").assertIsDisplayed()
        compose.onNodeWithTag("box.edit.save").assertIsNotEnabled()
    }

    @Test
    fun aTooLongNameIsExplained() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.edit").performClick()
        compose.onNodeWithTag("box.edit.name").performTextReplacement("x".repeat(201))
        compose.onNodeWithText("The name is too long (200 characters maximum).").assertIsDisplayed()
        compose.onNodeWithTag("box.edit.save").assertIsNotEnabled()
    }

    @Test
    fun anUnknownExpiryStaysUnknownInTheFormUntilTheUserChoosesADate() {
        val box = doliprane(expiry = null)
        show(box)
        compose.onNodeWithTag("box.edit").performClick()
        // "Date not printed" is on, nothing is pre-filled, and saving keeps the expiry unknown.
        compose.onNodeWithText("Date not printed").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Year")).fetchSemanticsNodes().size)
        compose.onNodeWithTag("box.edit.save").assertIsEnabled().performClick()
        assertNull(stored(box)?.expiry)
    }

    @Test
    fun theExpiryOfADatedBoxIsPrefilledAndCanBeChanged() {
        val box = doliprane(expiry = CabinetTestKit.FINE)
        show(box)
        compose.onNodeWithTag("box.edit").performClick()
        compose.onNodeWithTag("expiry.year").performTextReplacement("2029")
        compose.onNodeWithTag("box.edit.save").performClick()
        val saved = assertNotNull(stored(box)?.expiry)
        assertEquals(2029, saved.year)
        assertEquals(6, saved.month)
        assertNull(saved.day)
    }

    @Test
    fun anImpossibleExpiryCannotBeSaved() {
        val box = doliprane(expiry = CabinetTestKit.FINE)
        show(box)
        compose.onNodeWithTag("box.edit").performClick()
        compose.onNodeWithTag("expiry.year").performTextReplacement("")
        compose.onNodeWithTag("box.edit.save").assertIsNotEnabled()
    }

    @Test
    fun theLocationCanBeChanged() {
        val box = doliprane()
        val garage = runBlocking { kit.store.create("Garage") }
        show(box)
        compose.onNodeWithTag("box.edit").performClick()
        compose.onNodeWithTag("box.edit.location").performScrollTo().performClick()
        compose.onNodeWithText("Garage").performClick()
        compose.onNodeWithTag("box.edit.save").performScrollTo().performClick()
        assertEquals(garage.id, stored(box)?.locationId)
    }

    @Test
    fun choosingACategoryMarksItAsSetByYouAndAutomaticGivesItBackToTheClassifier() {
        val box = runBlocking {
            kit.open()
            kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE)
        }
        show(box)
        compose.onNodeWithTag("box.edit").performClick()
        compose.onNodeWithTag("box.edit.category.automatic").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("box.edit.category.choose").performScrollTo().performClick()
        compose.onNodeWithTag("box.edit.category.eyes").performClick()
        compose.onNodeWithTag("box.edit.category").assertIsDisplayed()
        compose.onNodeWithText("Set by you").assertIsDisplayed()
        compose.onNodeWithTag("box.edit.save").performScrollTo().performClick()
        assertEquals(CategoryID.EYES, stored(box)?.category)
        assertEquals(true, stored(box)?.categoryOverridden)

        compose.onNodeWithTag("box.edit").performClick()
        compose.onNodeWithTag("box.edit.category.automatic").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("box.edit.save").performScrollTo().performClick()
        assertEquals(CategoryID.PAIN_FEVER, stored(box)?.category)
        assertEquals(false, stored(box)?.categoryOverridden)
    }

    @Test
    fun usedUpLeavesTheScreenAndOffersAnUndoThatPutsTheBoxBack() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.takeOut").performClick()
        compose.onNodeWithTag("box.takeOut.usedUp").performClick()
        assertEquals(RemovalReason.USED_UP, stored(box)?.removal?.reason)
        compose.waitForIdle()
        assertEquals(1, backs)
        compose.onNodeWithText("Doliprane 1000: used up").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertHasClickAction().performClick()
        assertNull(stored(box)?.removal)
    }

    @Test
    fun thrownAwayRecordsThatReasonWithoutAnyConfirmation() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.takeOut").performClick()
        compose.onNodeWithTag("box.takeOut.thrownAway").performClick()
        assertEquals(RemovalReason.THROWN_AWAY, stored(box)?.removal?.reason)
        compose.onNodeWithText("Doliprane 1000: thrown away").assertIsDisplayed()
    }

    @Test
    fun addedByMistakeExplainsItIsNotAStockMovementThenDeletesAndUndoRestores() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.takeOut").performClick()
        compose.onNodeWithText("Deletes the box without counting it as used up or thrown away.").assertIsDisplayed()
        compose.onNodeWithTag("box.takeOut.mistake").performClick()
        assertNull(stored(box))
        compose.onNodeWithText("Doliprane 1000: deleted").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        assertNotNull(stored(box))
    }

    @Test
    fun undoingADeletionWhoseSerialWasTakenSaysSo() {
        val box = doliprane()
        show(box)
        compose.onNodeWithTag("box.takeOut").performClick()
        compose.onNodeWithTag("box.takeOut.mistake").performClick()
        runBlocking { kit.box("Doliprane", CabinetTestKit.DOLIPRANE, CabinetTestKit.FINE, serial = "SERIAL-SECRET-1") }
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Can't restore: another box has this serial number.").assertIsDisplayed()
        assertNull(stored(box))
    }

    @Test
    fun aFailedTakeOutKeepsTheBoxAndSaysNothingWasChanged() {
        val box = doliprane()
        show(box, kit.readOnlyEnvironment())
        compose.onNodeWithTag("box.takeOut").performClick()
        compose.onNodeWithTag("box.takeOut.usedUp").performClick()
        compose.onNodeWithTag("box.error").assertIsDisplayed()
        compose.onNodeWithText("Can't save the change.").assertIsDisplayed()
        assertNull(stored(box)?.removal)
        assertEquals(0, backs)
    }

    @Test
    fun aBoxThatLeavesTheCabinetTakesTheScreenBack() {
        val box = doliprane()
        show(box)
        runBlocking { kit.store.markRemoved(box.id, RemovalReason.USED_UP, kit.clock.now()) }
        compose.waitForIdle()
        assertTrue(backs >= 1)
    }

    @Test
    fun anUnreadableCabinetShowsAnErrorWithRetry() {
        val box = doliprane()
        show(box, kit.failingEnvironment())
        compose.onNodeWithText("Can't read the cabinet").assertIsDisplayed()
        compose.onNodeWithTag("box.retry").assertHasClickAction().performClick()
        compose.onNodeWithText("Can't read the cabinet").assertIsDisplayed()
    }

    @Test
    fun everyActionStaysReachableAtTwiceTheFontSize() {
        val box = doliprane()
        RuntimeEnvironment.setFontScale(2f)
        show(box)
        compose.onNodeWithTag("box.edit").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("box.opened").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("box.takeOut").performScrollTo().assertIsDisplayed().assertHasClickAction()
    }
}
