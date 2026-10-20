package com.medicabinet.app.features.scan

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Remove mode of the Scan tab with a fake camera (requirements R1-R3, R6-R8; issue #21). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class ScanScreenRemovalTest {
    @get:Rule
    val compose = createComposeRule()

    private val application get() = ApplicationProvider.getApplicationContext<Application>()

    private fun show(kit: ScanTestKit, beforeShow: suspend () -> Unit = {}) {
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        runBlocking {
            kit.open()
            beforeShow()
        }
        compose.setContent { MediCabinetTheme { ScanScreen(kit.model, kit.services) } }
        compose.waitForIdle()
    }

    private fun scan(kit: ScanTestKit, payload: String, at: Double = 0.0) {
        runBlocking {
            kit.scanner.awaitCollector()
            kit.see(payload, at = at)
        }
        compose.waitForIdle()
    }

    private fun stock(kit: ScanTestKit, serial: String?, expiry: ExpiryDate?, opened: Boolean = false) = runBlocking {
        kit.store.insert(
            Box(
                product = ProductRef(ScanTestKit.GTIN, "DOLIPRANE"),
                locationId = kit.defaultLocation.id,
                expiry = expiry,
                serial = serial,
                isOpened = opened,
                createdAt = ScanTestKit.START
            )
        )
    }

    private fun tag(value: String) = compose.onNodeWithTag(value)

    private fun text(value: String) = compose.onNodeWithText(value)

    @Test
    fun theScannerOpensInAddModeEvenWhenRemoveWasLeftOn() {
        val kit = ScanTestKit()
        show(kit) { kit.model.setMode(ScanMode.REMOVE) }
        compose.waitForIdle()
        tag("scan.mode.add").assertIsSelected()
        assertEquals(ScanMode.ADD, kit.model.state.value.mode)
    }

    @Test
    fun removeSaysSoInTextAndIconAndOffersTheReason() {
        val kit = ScanTestKit()
        show(kit)
        tag("scan.mode.remove").performClick()
        compose.waitForIdle()
        text("Removing boxes").assertIsDisplayed()
        tag("scan.removal.reason").assertIsDisplayed()
        text("Used up").assertIsDisplayed()
        text("Boxes removed: 0").assertIsDisplayed()
        tag("scan.mode.remove").assertIsSelected()
    }

    @Test
    fun theReasonIsChangedInOneTapAndRemembered() {
        val kit = ScanTestKit()
        show(kit)
        tag("scan.mode.remove").performClick()
        tag("scan.removal.reason").performClick()
        tag("scan.removal.reason.thrownAway").performClick()
        compose.waitForIdle()
        assertEquals(RemovalReason.THROWN_AWAY, kit.preferences.removalReason)
        text("Thrown away").assertIsDisplayed()
    }

    @Test
    fun scanningAKnownBoxTakesItOutAndUndoPutsItBack() {
        val kit = ScanTestKit()
        show(kit)
        scan(kit, ScanTestKit.payload(), at = 0.0)
        tag("scan.mode.remove").performClick()
        compose.waitForIdle()
        scan(kit, ScanTestKit.payload(), at = 10.0)
        text("Box removed: used up").assertIsDisplayed()
        text("Boxes removed: 1").assertIsDisplayed()
        assertEquals(0, runBlocking { kit.storedBoxes() }.size)
        tag("scan.notice.undoRemoval").performClick()
        compose.waitForIdle()
        assertEquals(1, runBlocking { kit.storedBoxes() }.size)
        text("Boxes removed: 0").assertIsDisplayed()
    }

    @Test
    fun aBoxThatIsNotInTheCabinetIsReportedAndNeverAdded() {
        val kit = ScanTestKit()
        show(kit)
        tag("scan.mode.remove").performClick()
        compose.waitForIdle()
        scan(kit, ScanTestKit.payload())
        text("Not in the cabinet").assertIsDisplayed()
        assertEquals(0, runBlocking { kit.storedBoxes() }.size)
    }

    @Test
    fun severalMatchingBoxesAskWhichOneToRemove() {
        val kit = ScanTestKit()
        show(kit) {
            stock(kit, null, ScanTestKit.date(2028, 1, 31))
            stock(kit, null, ScanTestKit.date(2026, 12, 31), opened = true)
        }
        tag("scan.mode.remove").performClick()
        compose.waitForIdle()
        scan(kit, ScanTestKit.payload(serial = null))
        text("Which box?").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithTag("candidate.row").fetchSemanticsNodes().size)
        text("Opened").assertIsDisplayed()
        tag("candidate.remove").performClick()
        compose.waitForIdle()
        assertNotNull(kit.model.state.value.removalSession.boxes.single().removal)
        assertEquals(1, runBlocking { kit.storedBoxes() }.size)
    }

    @Test
    fun cancellingTheSheetRemovesNothing() {
        val kit = ScanTestKit()
        show(kit) {
            stock(kit, null, ScanTestKit.date(2028, 1, 31))
            stock(kit, null, ScanTestKit.date(2026, 12, 31))
        }
        tag("scan.mode.remove").performClick()
        compose.waitForIdle()
        scan(kit, ScanTestKit.payload(serial = null))
        tag("candidate.cancel").performClick()
        compose.waitForIdle()
        assertNull(kit.model.state.value.candidates)
        assertEquals(2, runBlocking { kit.storedBoxes() }.size)
    }

    @Test
    fun theSessionListAndTheSummaryCoverWhatWasTakenOut() {
        val kit = ScanTestKit()
        show(kit) { stock(kit, "SER1", ScanTestKit.date(2027, 3, 31)) }
        tag("scan.mode.remove").performClick()
        compose.waitForIdle()
        scan(kit, ScanTestKit.payload())
        tag("scan.count").performClick()
        compose.waitForIdle()
        tag("session.row").assertIsDisplayed()
        tag("session.undoRemoval").assertIsDisplayed()
        tag("session.close").performClick()
        compose.waitForIdle()
        tag("scan.done").performClick()
        compose.waitForIdle()
        tag("summary.usedUp").assertIsDisplayed()
        text("1 box used up").assertIsDisplayed()
        text("0 boxes thrown away").assertIsDisplayed()
        tag("summary.close").performClick()
        compose.waitForIdle()
        text("Boxes removed: 0").assertIsDisplayed()
    }

    @Test
    fun removeModeRemainsUsableAtTwiceTheFontSize() {
        RuntimeEnvironment.setFontScale(2f)
        val kit = ScanTestKit()
        show(kit)
        tag("scan.mode.remove").performClick()
        compose.waitForIdle()
        listOf("scan.count", "scan.done", "scan.sound", "scan.removal.reason", "scan.mode.add").forEach {
            tag(it).assertIsDisplayed()
        }
    }
}
