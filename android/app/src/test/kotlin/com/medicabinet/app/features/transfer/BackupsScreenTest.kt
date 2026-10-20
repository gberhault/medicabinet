package com.medicabinet.app.features.transfer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.medicabinet.app.ui.theme.MediCabinetTheme
import com.medicabinet.core.archive.ArchiveBackupEntry
import com.medicabinet.core.archive.ArchiveBackupStore
import com.medicabinet.core.archive.BackupReason
import com.medicabinet.core.archive.CabinetTransfer
import com.medicabinet.core.testing.SyntheticArchive
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B4, B5: the list, newest first, unreadable rows shown as such, restore offered for readable ones. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class BackupsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val kit = TransferTestKit()
    private val archive = SyntheticArchive.json(locations = 1, boxes = 4).toByteArray()

    /** A backup store whose listing fails until [working]. */
    private class FlakyBackups(private val delegate: ArchiveBackupStore) : ArchiveBackupStore by delegate {
        var working = false

        override suspend fun list(): List<ArchiveBackupEntry> = if (working) delegate.list() else throw IOException("x")
    }

    private fun show(transfer: CabinetTransfer = kit.transfer, onRestore: (ArchiveBackupEntry) -> Unit = {}) {
        val model = BackupsViewModel(transfer, Dispatchers.Unconfined)
        compose.setContent {
            MediCabinetTheme { BackupsScreen(model, 0L, onRestore, onBack = {}, zone = ZoneOffset.UTC) }
        }
    }

    @Test
    fun noBackupsSaysWhenOneIsMade() {
        show()
        compose.onNodeWithText("No backups. A backup is made before every import.").assertIsDisplayed()
        compose.onNodeWithText("The last 3 cabinets replaced by an import or a restore.").assertIsDisplayed()
    }

    @Test
    fun backupsAreListedNewestFirstWithWhyAndHowManyBoxes() {
        kit.backups.write(archive, BackupReason.BEFORE_IMPORT, Instant.parse("2027-01-10T09:30:00Z"))
        kit.backups.write(archive, BackupReason.BEFORE_RESTORE, Instant.parse("2027-01-12T09:30:00Z"))
        show()
        compose.onAllNodesWithTag("backups.row").assertCountEquals(2)
        compose.onNodeWithText("Before an import").assertIsDisplayed()
        compose.onNodeWithText("Before a restore").assertIsDisplayed()
        compose.onAllNodesWithTag("backups.restore").assertCountEquals(2)
        compose.onNodeWithText("Jan 12, 2027", substring = true).assertIsDisplayed()
        compose.onAllNodes(androidx.compose.ui.test.hasText("4 boxes")).assertCountEquals(2)
    }

    @Test
    fun anUnreadableBackupIsListedWithoutRestore() {
        val entry = kit.backups.write(archive, BackupReason.BEFORE_IMPORT, Instant.parse("2027-01-10T09:30:00Z"))
        kit.backups.corrupt(entry.id)
        show()
        compose.onNodeWithText("Unreadable").assertIsDisplayed()
        compose.onAllNodesWithTag("backups.restore").assertCountEquals(0)
    }

    @Test
    fun restoreHandsTheChosenBackupOver() {
        val entry = kit.backups.write(archive, BackupReason.BEFORE_IMPORT, Instant.parse("2027-01-10T09:30:00Z"))
        var chosen: ArchiveBackupEntry? = null
        show { chosen = it }
        compose.onNodeWithContentDescription("Restore the backup of", substring = true).performClick()
        assertEquals(entry.id, chosen?.id)
    }

    @Test
    fun aListThatCannotBeReadSaysSoAndCanBeTriedAgain() {
        val flaky = FlakyBackups(kit.backups)
        show(CabinetTransfer(kit.store, flaky, kit.clock, kit.app))
        compose.onNodeWithText("The backups could not be read.").assertIsDisplayed()
        flaky.working = true
        compose.onNodeWithTag("backups.retry").performClick()
        compose.onNodeWithText("No backups. A backup is made before every import.").assertIsDisplayed()
    }

    @Test
    fun theModelStartsLoading() {
        assertIs<BackupsState.Loading>(BackupsViewModel(kit.transfer, Dispatchers.Unconfined).state.value)
    }
}
