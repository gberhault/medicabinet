package com.medicabinet.app.features.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medicabinet.app.app.AppLog
import com.medicabinet.core.archive.ArchiveException
import com.medicabinet.core.archive.BackupReason
import com.medicabinet.core.archive.CabinetTransfer
import com.medicabinet.core.archive.ImportPreview
import com.medicabinet.core.archive.ImportResult
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What is being imported. */
sealed interface ImportRequest {
    val reason: BackupReason

    /** A file opened, shared or picked: what the coordinator handed over (`null` if nothing was waiting any more). */
    class File(val incoming: Incoming?) : ImportRequest {
        override val reason: BackupReason get() = BackupReason.BEFORE_IMPORT
    }

    /** An automatic backup chosen in Settings (B5): the same flow, titled with the backup's date. */
    data class Backup(val id: String, val takenAt: Instant) : ImportRequest {
        override val reason: BackupReason get() = BackupReason.BEFORE_RESTORE
    }
}

sealed interface ImportState {
    /** "Reading the file…" (a backup is read from the app's storage). */
    data object Reading : ImportState

    /** "Checking the file…": decode, migrate, validate, compare with the phone. */
    data object Checking : ImportState

    /** The preview (I1); [confirming] while the "Replace your cabinet?" dialog is shown (I2). */
    data class Preview(val preview: ImportPreview, val confirming: Boolean = false) : ImportState

    /** The backup then the replace, in one transaction; [backingUp] when there is a cabinet to back up. */
    data class Applying(val backingUp: Boolean) : ImportState

    /** Replaced (I7). */
    data class Done(val result: ImportResult) : ImportState

    data class Failed(val failure: ImportFailure) : ImportState
}

/**
 * The import flow (I1–I10, B5, design flows 3–6, 8): read, check, preview, explicit confirmation, then the backup and
 * the replace in one transaction. [onReplaced] tells every screen the cabinet changed (the environment's revision);
 * [afterReplace] then starts the fill of the named products (a version 1 file brought none and left the fill pending).
 * Nothing is written before the confirmation; a refused file changes nothing.
 */
class ImportViewModel(
    private val transfer: CabinetTransfer,
    val request: ImportRequest,
    private val onReplaced: () -> Unit,
    private val io: CoroutineDispatcher,
    private val afterReplace: () -> Unit = {}
) : ViewModel() {
    private val mutableState = MutableStateFlow<ImportState>(ImportState.Reading)
    val state: StateFlow<ImportState> = mutableState.asStateFlow()

    private var preview: ImportPreview? = null

    init {
        viewModelScope.launch { check(read() ?: return@launch) }
    }

    /** The file's bytes, or `null` after moving to the failure. */
    private suspend fun read(): ByteArray? = when (request) {
        is ImportRequest.File -> when (val incoming = request.incoming) {
            is Incoming.Ready -> incoming.bytes
            is Incoming.Unreadable -> fail("read", null, incoming.failure)
            Incoming.Reading, null -> fail("read", null, ImportFailure.READ_FAILED)
        }
        is ImportRequest.Backup -> try {
            withContext(io) { transfer.readBackup(request.id) }
        } catch (error: CancellationException) {
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            fail("read", error, ImportFailure.READ_FAILED)
        }
    }

    private suspend fun check(bytes: ByteArray) {
        mutableState.value = ImportState.Checking
        mutableState.value = try {
            ImportState.Preview(withContext(io) { transfer.preview(bytes) }.also { preview = it })
        } catch (error: CancellationException) {
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            AppLog.transferFailed("preview", error, (error as? ArchiveException)?.kind)
            ImportState.Failed(ImportFailure.ofPreview(error))
        }
    }

    /** **Replace my cabinet**: asks once more (I2). */
    fun replace() {
        val current = mutableState.value as? ImportState.Preview ?: return
        mutableState.value = current.copy(confirming = true)
    }

    /** **Cancel** in the confirmation: back to the preview, nothing changed. */
    fun dismissConfirmation() {
        val current = mutableState.value as? ImportState.Preview ?: return
        mutableState.value = current.copy(confirming = false)
    }

    /** **Replace** in the confirmation. */
    fun confirm() {
        if ((mutableState.value as? ImportState.Preview)?.confirming != true) return
        apply()
    }

    /** **Try again** after the backup or the replace failed (the cabinet is unchanged, I3, I6). */
    fun retry() {
        val failed = mutableState.value as? ImportState.Failed ?: return
        if (failed.failure.canRetry && preview != null) apply()
    }

    private fun apply() {
        val previewed = preview ?: return
        mutableState.value = ImportState.Applying(backingUp = previewed.willBackUp)
        viewModelScope.launch {
            // Once started, the replace and the revision bump always finish together, even if the screen goes away.
            mutableState.value = withContext(NonCancellable) {
                try {
                    val result = withContext(io) { transfer.apply(previewed, request.reason) }
                    onReplaced()
                    afterReplace()
                    if (result.pruneFailed) AppLog.transferFailed("prune", null)
                    ImportState.Done(result)
                } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                    AppLog.transferFailed("apply", error)
                    ImportState.Failed(ImportFailure.ofApply(error))
                }
            }
        }
    }

    private fun fail(stage: String, error: Throwable?, failure: ImportFailure): ByteArray? {
        AppLog.transferFailed(stage, error)
        mutableState.value = ImportState.Failed(failure)
        return null
    }
}
