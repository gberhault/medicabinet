package com.medicabinet.app.features.transfer

import com.medicabinet.core.archive.ArchiveDecoder
import com.medicabinet.core.archive.BackupReason
import com.medicabinet.core.archive.CabinetContents
import com.medicabinet.core.archive.CabinetSummary
import com.medicabinet.core.testing.ArchiveFixtureFiles
import com.medicabinet.core.testing.InMemoryArchiveStore
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
import org.junit.Test

/** I1–I10, B5: preview, confirmation, backup and replace; every failure leaves the cabinet as it was. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val kit = TransferTestKit()
    private var replaced = 0
    private val golden = ArchiveFixtureFiles.goldenArchive()

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun model(request: ImportRequest, transfer: com.medicabinet.core.archive.CabinetTransfer = kit.transfer) =
        ImportViewModel(transfer, request, { replaced++ }, dispatcher)

    private fun file(bytes: ByteArray) = model(ImportRequest.File(Incoming.Ready(bytes)))

    private fun snapshot(): CabinetContents = runBlocking { kit.store.snapshot() }

    private fun ImportViewModel.preview() = assertIs<ImportState.Preview>(state.value)

    @Test
    fun aValidFileIsPreviewedAgainstThePhoneWithoutChangingAnything() {
        val before = snapshot()
        val preview = file(golden).preview().preview
        assertEquals(ArchiveDecoder.decode(golden).cabinet.summary, preview.file)
        assertEquals(CabinetSummary(2, 3, 0, 0, 0), preview.current)
        assertTrue(preview.willBackUp)
        assertEquals(1, preview.archive.sourceFormatVersion)
        assertEquals(before, snapshot())
        assertEquals(0, replaced)
    }

    @Test
    fun replacingAsksOnceMoreAndCancelChangesNothing() {
        val model = file(golden)
        model.confirm()
        assertFalse(model.preview().confirming, "nothing is applied without the question")
        model.replace()
        assertTrue(model.preview().confirming)
        model.dismissConfirmation()
        assertFalse(model.preview().confirming)
        assertEquals(TransferTestKit.sampleCabinet(), snapshot())
    }

    @Test
    fun confirmingBacksUpThenReplacesAndTellsEveryScreen() {
        val before = snapshot()
        val model = file(golden)
        model.replace()
        model.confirm()
        val result = assertIs<ImportState.Done>(model.state.value).result
        assertEquals(ArchiveDecoder.decode(golden).cabinet, snapshot())
        assertEquals(1, replaced)
        val backup = assertNotNull(result.backup)
        assertEquals(BackupReason.BEFORE_IMPORT, backup.reason)
        assertEquals(before.summary, backup.summary)
    }

    @Test
    fun aPhoneWithNothingToLoseTakesNoBackup() {
        val fresh = TransferTestKit(CabinetContents(TransferTestKit.sampleCabinet().locations.take(1), emptyList()))
        val model =
            ImportViewModel(fresh.transfer, ImportRequest.File(Incoming.Ready(golden)), { replaced++ }, dispatcher)
        assertFalse(model.preview().preview.willBackUp)
        model.replace()
        model.confirm()
        assertNull(assertIs<ImportState.Done>(model.state.value).result.backup)
        assertEquals(0, fresh.backups.count)
    }

    @Test
    fun aFailedBackupStopsEverythingAndCanBeTriedAgain() {
        kit.backups.setFailingWrites(true)
        val model = file(golden)
        model.replace()
        model.confirm()
        assertEquals(ImportState.Failed(ImportFailure.BACKUP_FAILED), model.state.value)
        assertEquals(TransferTestKit.sampleCabinet(), snapshot())
        assertEquals(0, replaced)

        kit.backups.setFailingWrites(false)
        model.retry()
        assertIs<ImportState.Done>(model.state.value)
        assertEquals(1, replaced)
    }

    @Test
    fun aFailedReplaceSaysSo() {
        val broken = object : com.medicabinet.core.archive.CabinetArchiveStore by InMemoryArchiveStore(
            TransferTestKit.sampleCabinet()
        ) {
            override suspend fun replace(snapshot: CabinetContents, beforeReplacing: (CabinetContents) -> Unit) =
                throw IOException("disk full")
        }
        val model = model(ImportRequest.File(Incoming.Ready(golden)), kit.transferOn(broken))
        model.replace()
        model.confirm()
        assertEquals(ImportState.Failed(ImportFailure.REPLACE_FAILED), model.state.value)
        assertEquals(0, replaced)
    }

    @Test
    fun aFileThatCannotBeReadOrWasNotThereIsReported() {
        assertEquals(
            ImportState.Failed(ImportFailure.READ_FAILED),
            model(ImportRequest.File(Incoming.Unreadable(ImportFailure.READ_FAILED))).state.value
        )
        assertEquals(ImportState.Failed(ImportFailure.READ_FAILED), model(ImportRequest.File(null)).state.value)
        assertEquals(
            ImportState.Failed(ImportFailure.READ_FAILED),
            model(ImportRequest.File(Incoming.Reading)).state.value
        )
    }

    @Test
    fun refusedFilesShowTheirKindAndCannotBeRetried() {
        val newer = String(golden).replaceFirst("\"formatVersion\": 1", "\"formatVersion\": 2").toByteArray()
        val damaged = String(golden).replaceFirst("\"month\": 4", "\"month\": 13").toByteArray()
        val cases = mapOf(
            "hello".toByteArray() to ImportFailure.NOT_ARCHIVE,
            "{\"a\":1}".toByteArray() to ImportFailure.NOT_ARCHIVE,
            newer to ImportFailure.NEWER_VERSION,
            damaged to ImportFailure.DAMAGED,
            ByteArray(com.medicabinet.core.archive.ArchiveLimits.DEFAULT.maxFileBytes + 1) to ImportFailure.TOO_LARGE
        )
        for ((bytes, failure) in cases) {
            val model = file(bytes)
            assertEquals(ImportState.Failed(failure), model.state.value, failure.name)
            model.retry()
            assertEquals(ImportState.Failed(failure), model.state.value)
        }
        assertEquals(TransferTestKit.sampleCabinet(), snapshot())
    }

    @Test
    fun aBackupIsRestoredThroughTheSameFlow() {
        val first = snapshot()
        val imported = file(golden)
        imported.replace()
        imported.confirm()
        val backup = assertNotNull(assertIs<ImportState.Done>(imported.state.value).result.backup)

        val restore = model(ImportRequest.Backup(backup.id, backup.takenAt))
        assertEquals(first.summary, restore.preview().preview.file)
        restore.replace()
        restore.confirm()
        val result = assertIs<ImportState.Done>(restore.state.value).result
        assertEquals(first, snapshot())
        assertEquals(BackupReason.BEFORE_RESTORE, result.backup?.reason)
    }

    @Test
    fun aBackupThatCannotBeReadIsReported() {
        val model = model(ImportRequest.Backup("missing", Instant.EPOCH))
        assertEquals(ImportState.Failed(ImportFailure.READ_FAILED), model.state.value)
    }

    @Test
    fun oldBackupsThatCannotBeDeletedDoNotFailTheImport() {
        kit.backups.setFailingPrunes(true)
        val model = file(golden)
        model.replace()
        model.confirm()
        assertTrue(assertIs<ImportState.Done>(model.state.value).result.pruneFailed)
    }
}
