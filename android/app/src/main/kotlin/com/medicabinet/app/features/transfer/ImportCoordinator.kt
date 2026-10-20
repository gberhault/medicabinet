package com.medicabinet.app.features.transfer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.medicabinet.app.R
import com.medicabinet.app.app.AppLog
import com.medicabinet.app.app.BootstrapUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file handed to the app (open with, share, or the in-app picker). */
sealed interface Incoming {
    /** Being read into memory. */
    data object Reading : Incoming

    /** Read; validation is the import flow's job. */
    class Ready(val bytes: ByteArray) : Incoming

    /** Could not be read at all. */
    data class Unreadable(val failure: ImportFailure) : Incoming
}

/** What to do with a waiting file now. */
enum class ImportGate {
    /** No file is waiting. */
    NONE,

    /** The cabinet is still opening, or the file is still being read: keep it. */
    WAIT,

    /** A form holds unsaved input: keep the file and say so (O4). */
    WAIT_BUSY,

    /** Show the import flow for it. */
    OPEN,

    /** The cabinet cannot be opened: refuse the file with that reason (O5). */
    REFUSE
}

/**
 * Receives archives opened or shared with the app, and the one picked in Settings (O2–O5, design §7.3). The bytes are
 * read **at once** (capped by the [read] function), because the URI grant can end with the activity. The file then
 * waits until the cabinet is open and no form holds unsaved input, and the import flow takes it.
 */
class ImportCoordinator(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val read: (Uri) -> ByteArray
) {
    private val incomingState = MutableStateFlow<Incoming?>(null)

    /** Each read gets a number: only the latest file received may land, whatever order the reads finish in. */
    private var latest = 0
    val incoming: StateFlow<Incoming?> = incomingState.asStateFlow()

    private val busyTokens = MutableStateFlow<Set<Any>>(emptySet())
    private val busyState = MutableStateFlow(false)

    /** True while a form with unsaved input (or an import already shown) is on screen. */
    val busy: StateFlow<Boolean> = busyState.asStateFlow()

    /**
     * Takes the archive an `ACTION_VIEW` or `ACTION_SEND` intent carries. Returns false for any other intent. Only
     * `content:` addresses are read: the activity is exported, so a `file:` address could name the app's own files.
     */
    fun receive(intent: Intent?): Boolean {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.streamExtra()
            else -> return false
        }
        if (uri != null && uri.scheme == CONTENT_SCHEME) {
            receive(uri)
        } else {
            synchronized(this) { latest++ }
            incomingState.value = Incoming.Unreadable(ImportFailure.READ_FAILED)
        }
        return true
    }

    /** Reads [uri] now, off the main thread. A newer file replaces one that was waiting. */
    fun receive(uri: Uri) {
        val ticket = synchronized(this) { ++latest }
        incomingState.value = Incoming.Reading
        scope.launch {
            val result = try {
                Incoming.Ready(withContext(io) { read(uri) })
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                AppLog.transferFailed("read", error)
                Incoming.Unreadable(ImportFailure.READ_FAILED)
            }
            synchronized(this@ImportCoordinator) { if (ticket == latest) incomingState.value = result }
        }
    }

    /** What to do with the waiting file, given where the launch is. */
    fun gate(bootstrap: BootstrapUiState): ImportGate {
        val waiting = incomingState.value ?: return ImportGate.NONE
        return when {
            bootstrap is BootstrapUiState.Failed -> ImportGate.REFUSE
            bootstrap is BootstrapUiState.Loading || waiting == Incoming.Reading -> ImportGate.WAIT
            busyState.value -> ImportGate.WAIT_BUSY
            else -> ImportGate.OPEN
        }
    }

    /** The import flow takes the file once it is read; `null` while it is still being read or when none waits. */
    fun take(): Incoming? {
        val current = incomingState.value
        if (current == null || current == Incoming.Reading) return null
        return if (incomingState.compareAndSet(current, null)) current else null
    }

    /** Drops the waiting file (refused because the cabinet cannot be opened, or the user closed the message). */
    fun discard() {
        synchronized(this) { latest++ }
        incomingState.value = null
    }

    /** [token] holds unsaved input (or is an import on screen) until [release]. */
    fun hold(token: Any) {
        busyTokens.update { it + token }
        busyState.value = busyTokens.value.isNotEmpty()
    }

    fun release(token: Any) {
        busyTokens.update { it - token }
        busyState.value = busyTokens.value.isNotEmpty()
    }

    private fun Intent.streamExtra(): Uri? = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)

    private companion object {
        const val CONTENT_SCHEME = "content"
    }
}

/** Set by the main scaffold; `null` outside it (isolated tests), where nothing waits for forms. */
val LocalImportCoordinator = staticCompositionLocalOf<ImportCoordinator?> { null }

/** While [active], an incoming file waits instead of interrupting this form (O4). */
@Composable
fun HoldsUnsavedInput(active: Boolean) {
    val coordinator = LocalImportCoordinator.current ?: return
    DisposableEffect(coordinator, active) {
        val token = Any()
        if (active) coordinator.hold(token)
        onDispose { coordinator.release(token) }
    }
}

/**
 * "A cabinet file is waiting. Finish or cancel the current entry." while a form holds the file back (O4). TalkBack
 * reads it when it appears.
 */
@Composable
fun PendingImportBanner(coordinator: ImportCoordinator, modifier: Modifier = Modifier) {
    val incoming by coordinator.incoming.collectAsState()
    val busy by coordinator.busy.collectAsState()
    if (incoming == null || !busy) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("import.pendingBanner"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.HourglassTop, contentDescription = null)
            Text(
                stringResource(R.string.transfer_import_pendingBanner),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/** The cabinet cannot be opened, so a file opened with the app is refused with that reason (O5). */
@Composable
fun RefusedImportDialog(coordinator: ImportCoordinator) {
    val incoming by coordinator.incoming.collectAsState()
    if (incoming == null) return
    AlertDialog(
        onDismissRequest = coordinator::discard,
        title = { Text(stringResource(R.string.transfer_error_title)) },
        text = { Text(stringResource(R.string.transfer_import_unavailable), Modifier.testTag("import.unavailable")) },
        confirmButton = {
            TextButton(onClick = coordinator::discard, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.transfer_error_close))
            }
        }
    )
}
