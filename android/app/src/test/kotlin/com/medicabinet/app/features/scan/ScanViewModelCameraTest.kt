package com.medicabinet.app.features.scan

import app.cash.turbine.test
import com.medicabinet.core.Location
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Camera states, the session location and the sound preference (requirements C1–C5, C7, C8). */
class ScanViewModelCameraTest {
    private fun camera(kit: ScanTestKit) = kit.model.state.value.camera

    @Test
    fun nothingIsShownAndNoCameraBuiltBeforeThePermissionIsChecked() {
        assertEquals(CameraState.CHECKING, camera(ScanTestKit()))
    }

    @Test
    fun withoutADecisionTheCameraExplainsWhyAndDoesNotStart() {
        val kit = ScanTestKit()
        kit.model.onCameraAccess(CameraAccess.NOT_DETERMINED)
        assertEquals(CameraState.NEEDS_PERMISSION, camera(kit))
    }

    @Test
    fun allowingTheCameraStartsIt() {
        val kit = ScanTestKit()
        kit.model.onCameraAccess(CameraAccess.NOT_DETERMINED)
        kit.model.onCameraAccess(CameraAccess.AUTHORIZED)
        assertEquals(CameraState.STARTING, camera(kit))
        kit.model.onPreviewStarted()
        assertEquals(CameraState.SCANNING, camera(kit))
    }

    @Test
    fun aDeniedCameraOffersSettings() {
        val kit = ScanTestKit()
        kit.model.onCameraAccess(CameraAccess.DENIED)
        assertEquals(CameraState.DENIED, camera(kit))
    }

    @Test
    fun aDeviceWithoutACameraIsUnsupportedWhateverThePermission() {
        val kit = ScanTestKit(hasCamera = false)
        CameraAccess.entries.forEach {
            kit.model.onCameraAccess(it)
            assertEquals(CameraState.UNSUPPORTED, camera(kit))
        }
    }

    @Test
    fun aScanningCameraIsNotRestartedByARecompositionOrAResume() {
        val kit = ScanTestKit()
        kit.model.onCameraAccess(CameraAccess.AUTHORIZED)
        kit.model.onPreviewStarted()
        kit.model.onCameraAccess(CameraAccess.AUTHORIZED)
        assertEquals(CameraState.SCANNING, camera(kit))
    }

    @Test
    fun theLoadedStateDoesNotChangeOnAPreviewStartOutsideStarting() {
        val kit = ScanTestKit()
        kit.model.onCameraAccess(CameraAccess.DENIED)
        kit.model.onPreviewStarted()
        assertEquals(CameraState.DENIED, camera(kit))
    }

    @Test
    fun aCameraThatCannotStartCanBeRetried() {
        val kit = ScanTestKit()
        kit.model.onCameraAccess(CameraAccess.AUTHORIZED)
        kit.model.onCameraFailed()
        assertEquals(CameraState.FAILED, camera(kit))
        val before = kit.model.state.value.attempt
        kit.model.retry()
        assertEquals(CameraState.STARTING, camera(kit))
        assertEquals(before + 1, kit.model.state.value.attempt)
    }

    @Test
    fun androidCannotTellNeverAskedFromRefusedForGoodSoTheAppRemembersIt() {
        assertEquals(
            CameraAccess.AUTHORIZED,
            CameraAccess.resolve(granted = true, requestedBefore = false, showsRationale = false)
        )
        assertEquals(
            CameraAccess.NOT_DETERMINED,
            CameraAccess.resolve(false, requestedBefore = false, showsRationale = false)
        )
        assertEquals(
            CameraAccess.NOT_DETERMINED,
            CameraAccess.resolve(false, requestedBefore = true, showsRationale = true)
        )
        assertEquals(CameraAccess.DENIED, CameraAccess.resolve(false, requestedBefore = true, showsRationale = false))
    }

    @Test
    fun theRequestIsRemembered() {
        val kit = ScanTestKit()
        assertFalse(kit.model.cameraRequestedBefore)
        kit.model.permissionRequested()
        assertTrue(kit.model.cameraRequestedBefore)
        assertTrue(kit.preferences.cameraRequested)
    }

    // location

    @Test
    fun theDefaultLocationIsPreselected() = runBlocking {
        val kit = ScanTestKit().open()
        assertEquals(kit.defaultLocation, kit.model.state.value.sessionLocation)
    }

    @Test
    fun theLastLocationIsPreselectedWhileItStillExists() = runBlocking {
        val kit = ScanTestKit()
        val default = kit.store.ensureDefault("Cabinet")
        val kitchen = Location(name = "Kitchen", createdAt = ScanTestKit.START)
        kit.store.insert(kitchen)
        kit.preferences.lastLocationId = kitchen.id
        kit.model.load()
        assertEquals(kitchen.id, kit.model.state.value.sessionLocation?.id)

        val other = ScanTestKit()
        val otherDefault = other.store.ensureDefault("Cabinet")
        other.preferences.lastLocationId = UUID.randomUUID()
        other.model.load()
        assertEquals(otherDefault, other.model.state.value.sessionLocation)
        assertEquals("Cabinet", default.name)
    }

    @Test
    fun changingTheLocationAppliesToBoxesAddedAfterwardsAndIsRemembered() = runBlocking {
        val kit = ScanTestKit().open()
        val kitchen = Location(name = "Kitchen", createdAt = ScanTestKit.START)
        kit.store.insert(kitchen)
        kit.model.load()
        kit.see(ScanTestKit.payload(serial = "A"), at = 0.0)
        kit.model.selectLocation(kitchen.id)
        kit.see(ScanTestKit.payload(serial = "B"), at = 0.1)

        val stored = kit.storedBoxes()
        assertEquals(kit.defaultLocation.id, stored.first { it.serial == "A" }.locationId)
        assertEquals(kitchen.id, stored.first { it.serial == "B" }.locationId)
        assertEquals(kitchen.id, kit.preferences.lastLocationId)
        assertEquals("Kitchen", kit.model.locationName(kitchen.id))
        kit.model.selectLocation(UUID.randomUUID())
        assertEquals(kitchen.id, kit.model.state.value.sessionLocation?.id)
        assertEquals("", kit.model.locationName(UUID.randomUUID()))
    }

    @Test
    fun soundIsOffByDefaultRememberedAndCarriedByTheFeedback() = runBlocking {
        val kit = ScanTestKit().open()
        assertFalse(kit.model.state.value.soundEnabled)
        kit.model.setSound(true)
        assertTrue(kit.preferences.soundEnabled)
        kit.model.feedback.test {
            kit.see(ScanTestKit.payload())
            assertEquals(FeedbackRequest(FeedbackEvent.ADDED, sound = true), awaitItem())
        }
    }

    @Test
    fun noLocationMeansNothingCanBeAdded() = runBlocking {
        val kit = ScanTestKit()
        kit.model.load()
        assertNull(kit.model.state.value.sessionLocation)
        kit.see(ScanTestKit.payload())
        assertEquals(Notice.Failed, kit.model.state.value.notice)
    }
}
