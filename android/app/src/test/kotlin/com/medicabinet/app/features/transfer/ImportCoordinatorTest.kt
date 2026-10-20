package com.medicabinet.app.features.transfer

import android.content.Intent
import android.net.Uri
import com.medicabinet.app.app.BootstrapFailure
import com.medicabinet.app.app.BootstrapUiState
import com.medicabinet.app.features.scan.testEnvironment
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** O2–O5: files are read at once, then wait for the cabinet and for forms with unsaved input. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImportCoordinatorTest {
    private val file: Uri = Uri.parse("content://com.android.providers.downloads.documents/document/42")
    private val reads = mutableListOf<Uri>()
    private val contents = mutableMapOf(file to "archive".toByteArray())
    private val dispatcher = UnconfinedTestDispatcher()
    private val coordinator = ImportCoordinator(TestScope(dispatcher), dispatcher) { uri ->
        reads += uri
        contents[uri] ?: throw IOException("gone")
    }
    private val ready = BootstrapUiState.Ready(testEnvironment())

    private fun ready(): ByteArray = assertIs<Incoming.Ready>(coordinator.incoming.value).bytes

    @Test
    fun aFileOpenedWithTheAppIsReadAtOnce() {
        assertTrue(coordinator.receive(Intent(Intent.ACTION_VIEW).setDataAndType(file, "application/octet-stream")))
        assertContentEquals("archive".toByteArray(), ready())
        assertEquals(listOf(file), reads)
    }

    @Test
    fun aFileSharedWithTheAppIsReadFromItsStream() {
        assertTrue(coordinator.receive(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, file)))
        assertContentEquals("archive".toByteArray(), ready())
    }

    @Test
    fun otherIntentsAreNotForTheImport() {
        assertFalse(coordinator.receive(Intent(Intent.ACTION_MAIN)))
        assertFalse(coordinator.receive(null))
        assertNull(coordinator.incoming.value)
    }

    @Test
    fun anAddressThatIsNotContentIsNeverRead() {
        val own = Uri.parse("file:///data/data/com.medicabinet.app/databases/cabinet.db")
        assertTrue(coordinator.receive(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, own)))
        assertEquals(Incoming.Unreadable(ImportFailure.READ_FAILED), coordinator.incoming.value)
        assertTrue(coordinator.receive(Intent(Intent.ACTION_SEND)))
        assertEquals(Incoming.Unreadable(ImportFailure.READ_FAILED), coordinator.incoming.value)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun aFileThatCannotBeReadIsReported() {
        coordinator.receive(Uri.parse("content://gone/1"))
        assertEquals(Incoming.Unreadable(ImportFailure.READ_FAILED), coordinator.incoming.value)
    }

    @Test
    fun theFileWaitsWhileTheCabinetOpensAndIsRefusedWhenItCannot() {
        assertEquals(ImportGate.NONE, coordinator.gate(BootstrapUiState.Loading))
        coordinator.receive(file)
        assertEquals(ImportGate.WAIT, coordinator.gate(BootstrapUiState.Loading))
        assertEquals(ImportGate.REFUSE, coordinator.gate(BootstrapUiState.Failed(BootstrapFailure.STORAGE)))
        assertEquals(ImportGate.OPEN, coordinator.gate(ready))
    }

    @Test
    fun theFileWaitsWhileAFormHoldsUnsavedInput() {
        coordinator.receive(file)
        val form = Any()
        val other = Any()
        coordinator.hold(form)
        coordinator.hold(other)
        assertEquals(ImportGate.WAIT_BUSY, coordinator.gate(ready))
        coordinator.release(form)
        assertTrue(coordinator.busy.value)
        coordinator.release(other)
        assertFalse(coordinator.busy.value)
        assertEquals(ImportGate.OPEN, coordinator.gate(ready))
    }

    @Test
    fun theImportTakesTheFileOnceAndNotWhileItIsRead() {
        val slow = StandardTestDispatcher()
        val scope = TestScope(slow)
        val pending = ImportCoordinator(scope, slow) { "archive".toByteArray() }
        pending.receive(file)
        assertEquals(ImportGate.WAIT, pending.gate(ready))
        assertNull(pending.take())
        scope.advanceUntilIdle()
        assertIs<Incoming.Ready>(pending.take())
        assertNull(pending.take())
        assertNull(pending.incoming.value)
    }

    @Test
    fun theLatestFileWinsWhateverOrderTheReadsFinishIn() {
        val slow = StandardTestDispatcher()
        val scope = TestScope(slow)
        val second = Uri.parse("content://downloads/2")
        val pending = ImportCoordinator(scope, slow) { uri -> uri.toString().toByteArray() }
        pending.receive(file)
        pending.receive(second)
        scope.advanceUntilIdle()
        assertContentEquals(second.toString().toByteArray(), assertIs<Incoming.Ready>(pending.incoming.value).bytes)
    }

    @Test
    fun aDiscardedFileIsGoneEvenIfItsReadFinishesLater() {
        val slow = StandardTestDispatcher()
        val scope = TestScope(slow)
        val pending = ImportCoordinator(scope, slow) { "archive".toByteArray() }
        pending.receive(file)
        pending.discard()
        scope.advanceUntilIdle()
        assertNull(pending.incoming.value)
    }
}

class ImportFailureTest {
    @Test
    fun validationKindsMapToTheMessagesOfStringsMd() {
        val expected = mapOf(
            "tooLarge" to ImportFailure.TOO_LARGE,
            "tooManyItems" to ImportFailure.TOO_LARGE,
            "notJSON" to ImportFailure.NOT_ARCHIVE,
            "notArchive" to ImportFailure.NOT_ARCHIVE,
            "newerVersion" to ImportFailure.NEWER_VERSION,
            "unsupportedVersion" to ImportFailure.DAMAGED,
            "invalidStructure" to ImportFailure.DAMAGED,
            "invalidValue" to ImportFailure.DAMAGED,
            "duplicateId" to ImportFailure.DAMAGED,
            "unknownLocation" to ImportFailure.DAMAGED,
            "defaultLocation" to ImportFailure.DAMAGED,
            "duplicateSerial" to ImportFailure.DAMAGED
        )
        for (kind in com.medicabinet.core.archive.ArchiveErrorKind.entries) {
            assertEquals(expected.getValue(kind.rawValue), ImportFailure.of(kind), kind.rawValue)
        }
    }

    @Test
    fun readAndApplyFailuresHaveTheirOwnMessages() {
        val refused = com.medicabinet.core.archive.ArchiveException(
            com.medicabinet.core.archive.ArchiveErrorKind.NEWER_VERSION
        )
        assertEquals(ImportFailure.NEWER_VERSION, ImportFailure.ofPreview(refused))
        assertEquals(ImportFailure.READ_FAILED, ImportFailure.ofPreview(IOException("gone")))
        val backup = com.medicabinet.core.archive.TransferException(
            com.medicabinet.core.archive.TransferErrorKind.BACKUP_FAILED
        )
        assertEquals(ImportFailure.BACKUP_FAILED, ImportFailure.ofApply(backup))
        assertEquals(ImportFailure.REPLACE_FAILED, ImportFailure.ofApply(IllegalStateException()))
        assertTrue(ImportFailure.BACKUP_FAILED.canRetry && ImportFailure.REPLACE_FAILED.canRetry)
        assertFalse(ImportFailure.DAMAGED.canRetry || ImportFailure.READ_FAILED.canRetry)
    }
}
