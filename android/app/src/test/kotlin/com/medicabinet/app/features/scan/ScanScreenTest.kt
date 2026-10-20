package com.medicabinet.app.features.scan

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.app.features.scan.camera.CodeScannerError
import com.medicabinet.app.ui.theme.MediCabinetTheme
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Scan tab end to end with a fake camera: permission states, scanning, prompts, sheets (C, K, R, M). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class ScanScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val application get() = ApplicationProvider.getApplicationContext<Application>()

    private fun grantCamera() = shadowOf(application).grantPermissions(Manifest.permission.CAMERA)

    private fun show(kit: ScanTestKit, dark: Boolean = false) {
        runBlocking { kit.open() }
        compose.setContent { MediCabinetTheme(darkTheme = dark) { ScanScreen(kit.model, kit.services) } }
        compose.waitForIdle()
    }

    private fun scan(kit: ScanTestKit, vararg payloads: String, at: Double = 0.0) {
        runBlocking {
            kit.scanner.awaitCollector()
            kit.see(*payloads, at = at)
        }
        compose.waitForIdle()
    }

    private fun text(value: String) = compose.onNodeWithText(value)

    private fun tag(value: String) = compose.onNodeWithTag(value)

    // permission and fallbacks

    @Test
    fun theCameraIsExplainedBeforeTheSystemPromptAndManualEntryIsOffered() {
        show(ScanTestKit())
        text("Allow the camera").assertIsDisplayed()
        tag("scan.permission.allow").assertIsDisplayed().assertIsEnabled()
        tag("scan.manual.open").assertIsDisplayed()
    }

    @Test
    fun aGrantedCameraStartsScanningWithLocationCountAndDone() {
        grantCamera()
        show(ScanTestKit())
        tag("scan.count").assertIsDisplayed()
        tag("scan.done").assertIsDisplayed()
        tag("scan.location").assertIsDisplayed()
        tag("scan.sound").assertIsDisplayed()
        text("Put the box's code in the frame").assertIsDisplayed()
    }

    @Test
    fun aCameraRefusedForGoodOffersSettingsAndManualEntry() {
        val kit = ScanTestKit().also { it.preferences.cameraRequested = true }
        show(kit)
        text("Camera turned off").assertIsDisplayed()
        tag("scan.permission.settings").performClick()
        val started = shadowOf(application).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${application.packageName}", started.data.toString())
        assertNotNull(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        tag("scan.manual.open").assertIsDisplayed()
    }

    @Test
    fun aDeviceWithoutACameraStillAllowsManualEntry() {
        show(ScanTestKit(hasCamera = false))
        text("Scanning unavailable").assertIsDisplayed()
        tag("scan.manual.open").assertIsDisplayed()
    }

    @Test
    fun aCameraThatFailsToStartCanBeRetried() {
        grantCamera()
        val kit = ScanTestKit()
        kit.scanner.failure = CodeScannerError.FAILED_TO_START
        show(kit)
        text("The camera didn't start").assertIsDisplayed()
        tag("scan.manual.open").assertIsDisplayed()
        tag("scan.camera.retry").performClick()
        compose.waitForIdle()
        assertEquals(1, kit.model.state.value.attempt)
    }

    // scanning

    @Test
    fun aScannedBoxIsConfirmedWithItsNameAndExpiryAndCounted() {
        grantCamera()
        val kit = ScanTestKit()
        show(kit)
        scan(kit, ScanTestKit.payload())
        text("DOLIPRANE").assertIsDisplayed()
        text("Expires March 31, 2027").assertIsDisplayed()
        tag("scan.notice.undo").assertIsDisplayed()
        assertEquals(1, kit.model.state.value.session.count)
    }

    @Test
    fun aDuplicateSerialIsRefusedWithItsLocation() {
        grantCamera()
        val kit = ScanTestKit()
        show(kit)
        scan(kit, ScanTestKit.payload(), at = 0.0)
        scan(kit, ScanTestKit.payload(), at = 5.0)
        text("Already in the cabinet: Cabinet").assertIsDisplayed()
        assertEquals(1, kit.model.state.value.session.count)
    }

    @Test
    fun anUnknownProductAsksForANameAndCancelAddsNothing() {
        grantCamera()
        val kit = ScanTestKit(StubLookup())
        show(kit)
        scan(kit, ScanTestKit.payload())
        tag("completion.name").assertIsDisplayed()
        tag("completion.add").assertIsNotEnabled()
        tag("completion.cancel").performClick()
        compose.waitForIdle()
        assertEquals(0, kit.model.state.value.session.count)
    }

    @Test
    fun aMissingDateIsAskedAndDateNotPrintedIsSavedAsUnknown() {
        grantCamera()
        val kit = ScanTestKit()
        show(kit)
        scan(kit, ScanTestKit.payload(expiry = null))
        tag("completion.add").assertIsNotEnabled()
        tag("expiry.notPrinted").performClick()
        tag("completion.add").assertIsEnabled().performClick()
        compose.waitForIdle()
        text("Expiry unknown").assertIsDisplayed()
        tag("scan.done").performClick()
        text("Without an expiry date: 1").assertIsDisplayed()
        tag("summary.close").performClick()
        compose.waitForIdle()
        assertEquals(0, kit.model.state.value.session.count)
    }

    @Test
    fun anImpossibleDateInTheCodeSaysSoAndIsNotPrefilled() {
        grantCamera()
        val kit = ScanTestKit()
        show(kit)
        scan(kit, ScanTestKit.payload(expiry = "271315"))
        tag("expiry.unreadable").assertIsDisplayed()
        tag("completion.add").assertIsNotEnabled()
    }

    @Test
    fun theDateCanBeTypedInAndAnInvalidOneIsRefused() {
        grantCamera()
        val kit = ScanTestKit()
        show(kit)
        scan(kit, ScanTestKit.payload(expiry = null))
        tag("expiry.month").performClick()
        tag("expiry.month.2").performClick()
        tag("expiry.year").performTextInput("2027")
        tag("expiry.day").performClick()
        tag("expiry.day.31").performScrollTo().performClick()
        tag("expiry.invalid").assertExists()
        tag("completion.add").assertIsNotEnabled()
        tag("expiry.day").performClick()
        tag("expiry.day.28").performScrollTo().performClick()
        tag("completion.add").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(ScanTestKit.date(2027, 2, 28), runBlocking { kit.storedBoxes() }.single().expiry)
    }

    @Test
    fun theSameProductWithoutASerialAsksBeforeAddingAnotherBox() {
        grantCamera()
        val kit = ScanTestKit()
        show(kit)
        val noSerial = ScanTestKit.payload(lot = null, serial = null)
        scan(kit, noSerial, at = 0.0)
        scan(kit, noSerial, at = 5.0)
        tag("scan.another").assertIsDisplayed()
        text("Another box of DOLIPRANE?").assertIsDisplayed()
        tag("scan.another.add").performClick()
        compose.waitForIdle()
        assertEquals(2, kit.model.state.value.session.count)
    }

    // session list, manual entry, summary

    @Test
    fun theSessionListShowsWhatWasAddedAndABoxCanBeDeletedThenRestored() {
        grantCamera()
        val kit = ScanTestKit()
        show(kit)
        scan(kit, ScanTestKit.payload())
        tag("scan.count").performClick()
        tag("session.list").assertIsDisplayed()
        tag("session.delete").performClick()
        compose.waitForIdle()
        tag("session.undoDelete").assertIsDisplayed()
        tag("session.undoDelete").performClick()
        compose.waitForIdle()
        tag("session.list").assertIsDisplayed()
        tag("session.close").performClick()
        compose.waitForIdle()
        tag("scan.count").assertIsDisplayed()
    }

    @Test
    fun anEmptySessionSaysSo() {
        grantCamera()
        show(ScanTestKit())
        tag("scan.count").performClick()
        text("No box added yet.").assertIsDisplayed()
    }

    @Test
    fun aRowCanBeCorrectedInline() {
        grantCamera()
        val kit = ScanTestKit()
        show(kit)
        scan(kit, ScanTestKit.payload())
        tag("scan.count").performClick()
        tag("session.row").performClick()
        tag("edit.name").assertIsDisplayed()
        tag("edit.save").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals("DOLIPRANE", runBlocking { kit.storedBoxes() }.single().product.name)
    }

    @Test
    fun aBoxCanBeTypedInWithoutTheCamera() {
        val kit = ScanTestKit()
        show(kit)
        tag("scan.manual.open").performClick()
        tag("manual.name").performTextInput("Thermometer")
        tag("manual.add").assertIsNotEnabled()
        tag("expiry.notPrinted").performClick()
        tag("manual.add").assertIsEnabled().performClick()
        compose.waitForIdle()
        val box = runBlocking { kit.storedBoxes() }.single()
        assertEquals("Thermometer", box.product.name)
        assertEquals(null, box.expiry)
    }

    @Test
    fun aWrongTypedCodeIsRefused() {
        val kit = ScanTestKit()
        show(kit)
        tag("scan.manual.open").performClick()
        tag("manual.code").performTextInput("12345")
        tag("manual.name").performTextInput("X")
        tag("expiry.notPrinted").performClick()
        tag("manual.add").performClick()
        compose.waitForIdle()
        text("Invalid code").assertIsDisplayed()
        assertEquals(0, runBlocking { kit.storedBoxes() }.size)
    }

    @Test
    fun theScannerRemainsUsableAtTwiceTheFontSizeInTheDarkTheme() {
        RuntimeEnvironment.setFontScale(2f)
        grantCamera()
        show(ScanTestKit(), dark = true)
        listOf("scan.count", "scan.done", "scan.sound", "scan.manual.open", "scan.location").forEach {
            tag(it).assertIsDisplayed()
        }
    }
}
