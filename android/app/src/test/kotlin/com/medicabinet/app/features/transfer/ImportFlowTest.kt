package com.medicabinet.app.features.transfer

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.features.MainScaffold
import com.medicabinet.app.features.scan.testScanServices
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.archive.ArchiveDecoder
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.ArchiveFixtureFiles
import com.medicabinet.core.testing.InMemoryCabinetStore
import com.medicabinet.core.testing.InMemoryReferenceData
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** I1, I2, I7, O4 on screen: an opened file leads to the preview, the confirmation and the result. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class ImportFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = TransferTestKit()
    private val golden = ArchiveFixtureFiles.goldenArchive()
    private val dispatcher = UnconfinedTestDispatcher()
    private val coordinator = ImportCoordinator(TestScope(dispatcher), dispatcher) { golden }
    private val environment = InMemoryCabinetStore(kit.clock).let { store ->
        AppEnvironment(
            store,
            store,
            kit.clock,
            InMemoryReferenceData(status = ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING)),
            transfer = kit.transfer
        )
    }

    private fun showApp() = compose.setContent {
        MediCabinetTheme {
            MainScaffold(environment, scanServices = testScanServices(), importCoordinator = coordinator)
        }
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun anOpenedFileShowsThePreviewThenReplacesTheCabinetAfterConfirmation() {
        showApp()
        coordinator.receive(Uri.parse("content://downloads/1"))
        waitFor("import.file")
        compose.onNodeWithText("In the file").assertIsDisplayed()
        compose.onNodeWithText("By MediCabinet 0.3.0 (iOS)").assertIsDisplayed()
        compose.onNodeWithText("File format: version 1").assertIsDisplayed()
        compose.onNodeWithText("On this phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("import.replaceStatement").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "A backup of the current cabinet will be kept on this phone. You can restore it."
        ).performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("import.replace").performScrollTo().performClick()
        compose.onNodeWithText("Replace your cabinet?").assertIsDisplayed()
        compose.onNodeWithTag("import.confirm.replace").performClick()

        waitFor("import.done")
        compose.onNodeWithText("Cabinet imported").assertIsDisplayed()
        compose.onNodeWithTag("import.backupKept").assertIsDisplayed()
        assertEquals(1L, environment.revision.value)
        assertEquals(ArchiveDecoder.decode(golden).cabinet, runBlocking { kit.store.snapshot() })

        compose.onNodeWithTag("import.doneClose").performScrollTo().performClick()
        waitFor("transfer.export")
    }

    @Test
    fun cancelLeavesTheCabinetAsItWas() {
        showApp()
        coordinator.receive(Uri.parse("content://downloads/1"))
        waitFor("import.cancel")
        compose.onNodeWithTag("import.replace").performScrollTo().performClick()
        compose.onNodeWithTag("import.confirm.cancel").performClick()
        compose.onNodeWithTag("import.cancel").performScrollTo().performClick()
        waitFor("transfer.export")
        assertEquals(TransferTestKit.sampleCabinet(), runBlocking { kit.store.snapshot() })
        assertEquals(0L, environment.revision.value)
    }

    @Test
    fun aFileFromANewerAppAsksToUpdateMediCabinet() {
        val newer = String(golden).replaceFirst("\"formatVersion\": 1", "\"formatVersion\": 2").toByteArray()
        val model = ImportViewModel(kit.transfer, ImportRequest.File(Incoming.Ready(newer)), {}, Dispatchers.Unconfined)
        compose.setContent { MediCabinetTheme { ImportScreen(model, onClose = {}) } }
        compose.onNodeWithText("Couldn't import").assertIsDisplayed()
        compose.onNodeWithText("This file comes from a newer version of MediCabinet. Update the app, then try again.")
            .assertIsDisplayed()
        compose.onNodeWithTag("import.retry").assertDoesNotExist()
    }

    @Test
    fun aFailedBackupOffersToTryAgain() {
        kit.backups.setFailingWrites(true)
        val model =
            ImportViewModel(kit.transfer, ImportRequest.File(Incoming.Ready(golden)), {}, Dispatchers.Unconfined)
        compose.setContent { MediCabinetTheme { ImportScreen(model, onClose = {}) } }
        compose.onNodeWithTag("import.replace").performScrollTo().performClick()
        compose.onNodeWithTag("import.confirm.replace").performClick()
        compose.onNodeWithText("The backup could not be saved. Your cabinet was not changed.").assertIsDisplayed()
        kit.backups.setFailingWrites(false)
        compose.onNodeWithTag("import.retry").performClick()
        waitFor("import.done")
    }

    @Test
    fun aRestoreIsTitledWithTheDateOfTheBackup() {
        val entry = kit.backups.write(
            golden,
            com.medicabinet.core.archive.BackupReason.BEFORE_IMPORT,
            Instant.parse("2027-01-10T09:30:00Z")
        )
        val model =
            ImportViewModel(kit.transfer, ImportRequest.Backup(entry.id, entry.takenAt), {}, Dispatchers.Unconfined)
        compose.setContent { MediCabinetTheme { ImportScreen(model, onClose = {}, zone = ZoneOffset.UTC) } }
        compose.onNodeWithText("Restore the backup of", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Jan 10, 2027", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFileWaitsWhileAFormIsOpenThenOpens() {
        val form = Any()
        coordinator.hold(form)
        showApp()
        coordinator.receive(Uri.parse("content://downloads/1"))
        waitFor("import.pendingBanner")
        compose.onNodeWithTag("import.file").assertDoesNotExist()
        coordinator.release(form)
        waitFor("import.file")
        compose.onNodeWithTag("import.pendingBanner").assertDoesNotExist()
    }
}
