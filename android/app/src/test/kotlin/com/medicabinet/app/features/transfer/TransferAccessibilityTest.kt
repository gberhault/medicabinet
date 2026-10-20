package com.medicabinet.app.features.transfer

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.archive.BackupReason
import com.medicabinet.core.archive.CabinetArchiveStore
import com.medicabinet.core.archive.CabinetContents
import com.medicabinet.core.testing.ArchiveFixtureFiles
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** X3, X4: 200 % font in the dark theme, 48 dp targets, TalkBack labels, headings and announced progress. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class TransferAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = TransferTestKit()
    private val golden = ArchiveFixtureFiles.goldenArchive()

    private fun hasLiveRegion() = SemanticsMatcher("is a live region") {
        it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
    }

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun bigAndDark(content: @androidx.compose.runtime.Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { MediCabinetTheme(darkTheme = true) { content() } }
    }

    @Test
    fun theExportSummaryStaysReachableWithLargeTargets() {
        val model = ExportViewModel(kit.transfer, kit.documents, kit.clock, { ZoneOffset.UTC }, Dispatchers.Unconfined)
        bigAndDark { TransferSection(model) }
        compose.onNodeWithText("Backup and transfer").assert(isHeading())
        compose.onNodeWithTag("transfer.export").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("transfer.import").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("transfer.backups").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("transfer.export").performClick()
        compose.onNode(hasText("Export the cabinet") and isHeading()).assertIsDisplayed()
        compose.onNodeWithTag("export.warning").performScrollTo().assertIsDisplayed()
        for (tag in listOf("export.save", "export.share", "export.cancel")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun thePreviewStaysReachableAndItsReplaceButtonIsLabelledByText() {
        val model =
            ImportViewModel(kit.transfer, ImportRequest.File(Incoming.Ready(golden)), {}, Dispatchers.Unconfined)
        bigAndDark { ImportScreen(model, onClose = {}, zone = ZoneOffset.UTC) }
        compose.onNodeWithText("In the file").assert(isHeading())
        compose.onNodeWithText("On this phone").performScrollTo().assert(isHeading())
        compose.onNodeWithTag("import.replaceStatement").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Replace my cabinet").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("import.cancel").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Cancel").assertIsDisplayed()
    }

    @Test
    fun progressIsAnnouncedWhileTheFileIsChecked() {
        val never = object : CabinetArchiveStore {
            override suspend fun snapshot(): CabinetContents = awaitCancellation()

            override suspend fun replace(snapshot: CabinetContents, beforeReplacing: (CabinetContents) -> Unit) = Unit
        }
        val model =
            ImportViewModel(kit.transferOn(never), ImportRequest.File(Incoming.Ready(golden)), {
            }, Dispatchers.Unconfined)
        compose.setContent { MediCabinetTheme { ImportScreen(model, onClose = {}) } }
        compose.onNodeWithText("Checking the file…").assert(hasLiveRegion())
    }

    @Test
    fun theResultIsAnnouncedAsAHeading() {
        val model =
            ImportViewModel(kit.transfer, ImportRequest.File(Incoming.Ready(golden)), {}, Dispatchers.Unconfined)
        compose.setContent { MediCabinetTheme { ImportScreen(model, onClose = {}) } }
        compose.onNodeWithTag("import.replace").performScrollTo().performClick()
        compose.onNodeWithTag("import.confirm.replace").performClick()
        compose.onNodeWithTag("import.done").assert(hasLiveRegion()).assert(isHeading())
        compose.onNodeWithTag("import.doneClose").performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aRestoreShowsItsWholeTitleAtLargeFontSizes() {
        val entry = kit.backups.write(golden, BackupReason.BEFORE_IMPORT, Instant.parse("2027-01-10T09:30:00Z"))
        val model =
            ImportViewModel(kit.transfer, ImportRequest.Backup(entry.id, entry.takenAt), {}, Dispatchers.Unconfined)
        bigAndDark { ImportScreen(model, onClose = {}, zone = ZoneOffset.UTC) }
        compose.onNodeWithTag("import.restoreTitle").assertIsDisplayed()
    }

    @Test
    fun eachRestoreButtonSaysWhichBackupItRestores() {
        kit.backups.write(golden, BackupReason.BEFORE_IMPORT, Instant.parse("2027-01-10T09:30:00Z"))
        val model = BackupsViewModel(kit.transfer, Dispatchers.Unconfined)
        bigAndDark { BackupsScreen(model, 0L, onRestore = {}, onBack = {}, zone = ZoneOffset.UTC) }
        compose.onNodeWithContentDescription("Restore the backup of", substring = true)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        // Material icon buttons draw 40 dp and extend their touch target to 48 dp themselves.
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun anEmptyBackupListIsAnnounced() {
        val model = BackupsViewModel(kit.transfer, Dispatchers.Unconfined)
        compose.setContent { MediCabinetTheme { BackupsScreen(model, 0L, onRestore = {}, onBack = {}) } }
        compose.onNodeWithTag("backups.message").assert(hasLiveRegion())
        assertEquals(0, kit.backups.count)
    }
}

/** X5: the same screens in French, the fallback language, with plurals and locale dates. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr")
class TransferFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = TransferTestKit()

    @Test
    fun thePreviewSpeaksFrench() {
        val golden = ArchiveFixtureFiles.goldenArchive()
        val model =
            ImportViewModel(kit.transfer, ImportRequest.File(Incoming.Ready(golden)), {}, Dispatchers.Unconfined)
        compose.setContent { MediCabinetTheme { ImportScreen(model, onClose = {}, zone = ZoneOffset.UTC) } }
        compose.onNodeWithText("Dans le fichier").assertIsDisplayed()
        compose.onNodeWithText("Par MediCabinet 0.7.0 (iOS)").assertIsDisplayed()
        compose.onNodeWithText("10 boîtes dans l'armoire").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Produits nommés par vous : 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remplacer mon armoire").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theExportSummarySpeaksFrench() {
        val model = ExportViewModel(kit.transfer, kit.documents, kit.clock, { ZoneOffset.UTC }, Dispatchers.Unconfined)
        compose.setContent { MediCabinetTheme { TransferSection(model) } }
        compose.onNodeWithText("Sauvegarde et transfert").assertIsDisplayed()
        compose.onNodeWithTag("transfer.export").performClick()
        compose.onNodeWithText("3 boîtes dans l'armoire").assertIsDisplayed()
        compose.onNodeWithText("1 boîte retirée (historique)").assertDoesNotExist()
        compose.onNodeWithText("0 boîte retirée (historique)").assertIsDisplayed()
        compose.onNodeWithText("2 lieux").assertIsDisplayed()
    }
}
