package com.medicabinet.app.features.transfer

import android.net.Uri
import app.cash.turbine.test
import com.medicabinet.core.archive.ArchiveDecoder
import com.medicabinet.core.archive.CabinetSummary
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** E1–E9: summary first, then save or share; failures say so and offer **Try again**; nothing is kept. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExportViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val kit = TransferTestKit()
    private val document: Uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Ax")

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun model(
        zone: ZoneId = ZoneOffset.UTC,
        transfer: com.medicabinet.core.archive.CabinetTransfer = kit.transfer
    ) = ExportViewModel(transfer, kit.documents, kit.clock, { zone }, dispatcher)

    @Test
    fun theSummaryComesFirstAndCancelExportsNothing() = runTest {
        val model = model()
        model.start()
        assertEquals(ExportState.Confirm(CabinetSummary(2, 3, 0, 0, 0)), model.state.value)
        model.cancel()
        assertEquals(ExportState.Idle, model.state.value)
        assertTrue(kit.documents.written.isEmpty() && kit.documents.shared.isEmpty())
    }

    @Test
    fun saveToFileAsksForADocumentNamedAfterTheLocalDayThenWritesTheArchive() = runTest {
        // 23:30 UTC on the 15th is already the 16th in Paris.
        val model = model(zone = ZoneId.of("Europe/Paris"))
        model.events.test {
            model.start()
            model.saveToFile()
            assertEquals(ExportEvent.CreateDocument("MediCabinet-2027-01-16.medicabinet"), awaitItem())
            model.onDocumentChosen(document)
            assertEquals(ExportEvent.Saved, awaitItem())
        }
        assertEquals(ExportState.Idle, model.state.value)
        val written = ArchiveDecoder.decode(kit.documents.written.getValue(document))
        assertEquals(kit.store.snapshot(), written.cabinet)
        assertEquals("android", written.app.platform)
    }

    @Test
    fun backingOutOfThePickerKeepsTheSummary() = runTest {
        val model = model()
        model.start()
        model.saveToFile()
        model.onDocumentChosen(null)
        assertIs<ExportState.Confirm>(model.state.value)
        assertTrue(kit.documents.written.isEmpty())
    }

    @Test
    fun aFailedWriteDeletesTheDocumentAndOffersToTryAgain() = runTest {
        val model = model()
        kit.documents.failWrites = true
        model.events.test {
            model.start()
            model.saveToFile()
            awaitItem()
            model.onDocumentChosen(document)
            assertEquals(ExportState.Failed(ExportStep.SAVE), model.state.value)
            assertEquals(listOf(document), kit.documents.deleted)

            kit.documents.failWrites = false
            model.retry()
            assertIs<ExportEvent.CreateDocument>(awaitItem())
            assertIs<ExportState.Confirm>(model.state.value)
        }
    }

    @Test
    fun shareHandsTheFileToTheShareSheet() = runTest {
        val model = model()
        model.events.test {
            model.start()
            model.share()
            assertEquals(ExportEvent.Share(kit.documents.shareUri), awaitItem())
        }
        val (name, bytes) = kit.documents.shared.single()
        assertEquals("MediCabinet-2027-01-15.medicabinet", name)
        assertEquals(kit.store.snapshot(), ArchiveDecoder.decode(bytes).cabinet)
        assertEquals(ExportState.Idle, model.state.value)
    }

    @Test
    fun aFailedShareCanBeTriedAgain() = runTest {
        val model = model()
        kit.documents.failShares = true
        model.start()
        model.share()
        assertEquals(ExportState.Failed(ExportStep.SHARE), model.state.value)
        kit.documents.failShares = false
        model.events.test {
            model.retry()
            assertIs<ExportEvent.Share>(awaitItem())
        }
    }

    @Test
    fun noAppToShareWithIsAFailureToo() = runTest {
        val model = model()
        model.start()
        model.onShareFailed()
        assertEquals(ExportState.Failed(ExportStep.SHARE), model.state.value)
    }

    @Test
    fun anUnreadableCabinetFailsTheSummaryAndRetryStartsAgain() = runTest {
        val model = model(transfer = kit.transferOn(FailingArchiveStore()))
        model.start()
        assertEquals(ExportState.Failed(ExportStep.SUMMARY), model.state.value)
        model.retry()
        assertEquals(ExportState.Failed(ExportStep.SUMMARY), model.state.value)
        model.cancel()
        model.retry()
        assertEquals(ExportState.Idle, model.state.value)
    }

    @Test
    fun saveAndShareNeedTheSummaryFirst() = runTest {
        val model = model()
        model.events.test {
            model.saveToFile()
            model.share()
            expectNoEvents()
        }
        assertEquals(ExportState.Idle, model.state.value)
        model.start()
        model.start()
        assertIs<ExportState.Confirm>(model.state.value)
    }

    @Test
    fun anEmptyCabinetCanBeExported() = runTest {
        val empty =
            TransferTestKit(
                com.medicabinet.core.archive.CabinetContents(
                    TransferTestKit.sampleCabinet().locations.take(1),
                    emptyList()
                )
            )
        val model = ExportViewModel(empty.transfer, empty.documents, empty.clock, { ZoneOffset.UTC }, dispatcher)
        model.start()
        assertEquals(ExportState.Confirm(CabinetSummary(1, 0, 0, 0, 0)), model.state.value)
        model.share()
        assertEquals(1, empty.documents.shared.size)
    }
}
