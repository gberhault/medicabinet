package com.medicabinet.app.features.transfer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medicabinet.app.app.AppLog
import com.medicabinet.core.Clock
import com.medicabinet.core.archive.ArchiveException
import com.medicabinet.core.archive.CabinetSummary
import com.medicabinet.core.archive.CabinetTransfer
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What failed, so **Try again** repeats that step. */
enum class ExportStep { SUMMARY, SAVE, SHARE }

sealed interface ExportState {
    data object Idle : ExportState

    /** Counting what will be exported ("Preparing the export…"). */
    data object Summarizing : ExportState

    /** The summary and the health-data warning; nothing has left the app (E2). */
    data class Confirm(val summary: CabinetSummary) : ExportState

    /** Encoding and writing the file ("Creating the file…"). */
    data object Writing : ExportState

    /** The file could not be made; the cabinet is unchanged (E7). */
    data class Failed(val step: ExportStep) : ExportState
}

/** One-off requests to the screen, which owns the system pickers. */
sealed interface ExportEvent {
    /** Open the system "create document" picker with this suggested name. */
    data class CreateDocument(val fileName: String) : ExportEvent

    /** Open the share sheet for this provider address. */
    data class Share(val uri: Uri) : ExportEvent

    /** The file was saved where the user chose (E8). */
    data object Saved : ExportEvent
}

/**
 * Export (E1–E9, design §7.1, flow 2): summary and warning first, then **Save to file** (the system picker, then the
 * archive is written to the chosen document) or **Share** (the archive is written to the export folder and handed to
 * the share sheet). Every file operation runs on [io]; the screen only shows states and runs the pickers.
 */
class ExportViewModel(
    private val transfer: CabinetTransfer,
    private val documents: ArchiveDocuments,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val io: CoroutineDispatcher
) : ViewModel() {
    private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ExportEvent>(Channel.BUFFERED)
    val events: Flow<ExportEvent> = eventChannel.receiveAsFlow()

    private var summary: CabinetSummary? = null

    /** **Export the cabinet**: counts what the file will hold, then asks (E2). */
    fun start() {
        if (mutableState.value != ExportState.Idle) return
        mutableState.value = ExportState.Summarizing
        viewModelScope.launch {
            mutableState.value = try {
                ExportState.Confirm(transfer.currentSummary().also { summary = it })
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                log("summary", error)
                ExportState.Failed(ExportStep.SUMMARY)
            }
        }
    }

    /** **Cancel** in the summary or the error: nothing was exported. */
    fun cancel() {
        mutableState.value = ExportState.Idle
    }

    /** **Save to file**: the screen opens the picker with today's file name (E4). */
    fun saveToFile() {
        if (mutableState.value !is ExportState.Confirm && mutableState.value !is ExportState.Failed) return
        summary?.let { mutableState.value = ExportState.Confirm(it) }
        eventChannel.trySend(ExportEvent.CreateDocument(CabinetTransfer.fileName(clock.now(), zone())))
    }

    /** The picker's answer; `null` when the user backed out of it (the summary stays). */
    fun onDocumentChosen(uri: Uri?) {
        if (uri == null) return
        write(ExportStep.SAVE, onFailure = { documents.deleteDocument(uri) }) { bytes ->
            documents.writeDocument(uri, bytes)
            ExportEvent.Saved
        }
    }

    /** **Share**: the file goes to the export folder, then to the share sheet. */
    fun share() {
        if (mutableState.value !is ExportState.Confirm && mutableState.value !is ExportState.Failed) return
        val name = CabinetTransfer.fileName(clock.now(), zone())
        write(ExportStep.SHARE, onFailure = {}) { bytes -> ExportEvent.Share(documents.shareFile(name, bytes)) }
    }

    /** The share sheet could not be opened (no app can receive the file). */
    fun onShareFailed() {
        mutableState.value = ExportState.Failed(ExportStep.SHARE)
    }

    /** **Try again** after a failure. */
    fun retry() {
        when ((mutableState.value as? ExportState.Failed)?.step) {
            ExportStep.SUMMARY -> {
                mutableState.value = ExportState.Idle
                start()
            }
            ExportStep.SAVE -> saveToFile()
            ExportStep.SHARE -> share()
            null -> Unit
        }
    }

    private fun write(step: ExportStep, onFailure: () -> Unit, deliver: (ByteArray) -> ExportEvent) {
        mutableState.value = ExportState.Writing
        viewModelScope.launch {
            try {
                val event = withContext(io) { deliver(transfer.exportArchive()) }
                mutableState.value = ExportState.Idle
                eventChannel.send(event)
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                log(step.name.lowercase(), error)
                withContext(io) { onFailure() }
                mutableState.value = ExportState.Failed(step)
            }
        }
    }

    private fun log(stage: String, error: Exception) =
        AppLog.transferFailed("export-$stage", error, (error as? ArchiveException)?.kind)
}
