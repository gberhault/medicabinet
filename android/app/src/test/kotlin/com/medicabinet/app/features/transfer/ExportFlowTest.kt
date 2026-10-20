package com.medicabinet.app.features.transfer

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.archive.CabinetContents
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** E1, E2, E5, E9: the section, the summary with the warning before anything leaves, then the share sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class ExportFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val warning =
        "This file contains health data (your medicines). It is not encrypted: anyone who gets it can read it. " +
            "Share it only with people you trust."

    private fun show(kit: TransferTestKit = TransferTestKit()): TransferTestKit {
        val model = ExportViewModel(kit.transfer, kit.documents, kit.clock, { ZoneOffset.UTC }, Dispatchers.Unconfined)
        compose.setContent { MediCabinetTheme { TransferSection(model) } }
        return kit
    }

    @Test
    fun theSectionOffersTheExportAndExplainsTheFile() {
        show()
        compose.onNodeWithText("Backup and transfer").assertIsDisplayed()
        compose.onNodeWithText("Export the cabinet").assertIsDisplayed()
        val footer = "A .medicabinet file holds the whole cabinet. " +
            "Use it as a backup or to move to another phone, iPhone or Android."
        compose.onNodeWithText(footer).assertIsDisplayed()
    }

    @Test
    fun theWarningAndTheCountsAreShownBeforeAnythingLeavesAndCancelSendsNothing() {
        val kit = show()
        compose.onNodeWithTag("transfer.export").performClick()
        compose.onNodeWithText("3 boxes in the cabinet").assertIsDisplayed()
        compose.onNodeWithText("0 removed boxes (history)").assertIsDisplayed()
        compose.onNodeWithText("2 locations").assertIsDisplayed()
        compose.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("export.cancel").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTagCount("export.sheet"))
        assertTrue(kit.documents.shared.isEmpty() && kit.documents.written.isEmpty())
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    fun shareOpensTheSystemChooserWithTheArchive() {
        val kit = show()
        compose.onNodeWithTag("transfer.export").performClick()
        compose.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("export.share").performScrollTo().performClick()
        compose.waitForIdle()
        val started = assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        assertEquals(1, kit.documents.shared.size)
    }

    @Test
    fun anEmptyCabinetSaysTheFileHoldsTheLocationsOnly() {
        show(TransferTestKit(CabinetContents(TransferTestKit.sampleCabinet().locations.take(1), emptyList())))
        compose.onNodeWithTag("transfer.export").performClick()
        compose.onNodeWithText("The cabinet has no box. The file will hold the locations only.").assertIsDisplayed()
    }

    @Test
    fun aFailedFileSaysTheCabinetIsUnchangedAndOffersToTryAgain() {
        val kit = TransferTestKit()
        kit.documents.failShares = true
        show(kit)
        compose.onNodeWithTag("transfer.export").performClick()
        compose.onNodeWithTag("export.share").performScrollTo().performClick()
        compose.onNodeWithText("The file could not be created. Your cabinet was not changed.").assertIsDisplayed()
        kit.documents.failShares = false
        compose.onNodeWithTag("export.retry").performClick()
        compose.waitForIdle()
        assertEquals(1, kit.documents.shared.size)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
