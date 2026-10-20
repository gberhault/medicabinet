@file:OptIn(ExperimentalMaterial3Api::class)

package com.medicabinet.app.features.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medicabinet.app.R
import com.medicabinet.app.app.AppLog
import com.medicabinet.core.archive.ArchiveBackupEntry
import com.medicabinet.core.archive.BackupReason
import com.medicabinet.core.archive.CabinetTransfer
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackupsState {
    data object Loading : BackupsState

    /** Newest first; an entry with no summary could not be read (B4). */
    data class Loaded(val entries: List<ArchiveBackupEntry>) : BackupsState

    data object Failed : BackupsState
}

/** The automatic backups, read from the app's private storage off the main thread (B4). */
class BackupsViewModel(private val transfer: CabinetTransfer, private val io: CoroutineDispatcher) : ViewModel() {
    private val mutableState = MutableStateFlow<BackupsState>(BackupsState.Loading)
    val state: StateFlow<BackupsState> = mutableState.asStateFlow()

    /** Also **Try again**, and after a restore (which adds a backup of the cabinet it replaced). */
    fun load() {
        if (mutableState.value !is BackupsState.Loaded) mutableState.value = BackupsState.Loading
        viewModelScope.launch {
            mutableState.value = try {
                BackupsState.Loaded(withContext(io) { transfer.backups() })
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                AppLog.transferFailed("backups", error)
                BackupsState.Failed
            }
        }
    }
}

/**
 * Settings → **Automatic backups** (B4, B5): the last cabinets replaced by an import or a restore, newest first; a
 * readable one can be restored through the import flow. [revision] reloads the list after a restore.
 */
@Composable
fun BackupsScreen(
    model: BackupsViewModel,
    revision: Long,
    onRestore: (ArchiveBackupEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val state by model.state.collectAsState()
    LaunchedEffect(revision) { model.load() }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_backups_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.about_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val current = state) {
                BackupsState.Loading -> Message(stringResource(R.string.transfer_backups_loading), progress = true)
                BackupsState.Failed -> {
                    Message(stringResource(R.string.transfer_backups_error))
                    OutlinedButton(
                        onClick = model::load,
                        modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("backups.retry")
                    ) { Text(stringResource(R.string.transfer_error_retry)) }
                }
                is BackupsState.Loaded -> if (current.entries.isEmpty()) {
                    Message(stringResource(R.string.transfer_backups_empty))
                } else {
                    BackupList(current.entries, zone, onRestore, Modifier.weight(1f))
                }
            }
            Text(
                stringResource(R.string.transfer_backups_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun Message(text: String, progress: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (progress) CircularProgressIndicator()
        Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("backups.message"))
    }
}

@Composable
private fun BackupList(
    entries: List<ArchiveBackupEntry>,
    zone: ZoneId,
    onRestore: (ArchiveBackupEntry) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier.testTag("backups.list")) {
        items(entries, key = { it.id }) { entry ->
            BackupRow(entry, zone, onRestore)
            HorizontalDivider()
        }
    }
}

@Composable
private fun BackupRow(entry: ArchiveBackupEntry, zone: ZoneId, onRestore: (ArchiveBackupEntry) -> Unit) {
    val date = TransferText.dateTime(entry.takenAt, LocalConfiguration.current.locales[0], zone)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("backups.row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(date, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(entry.reason.label), style = MaterialTheme.typography.bodyMedium)
            val summary = entry.summary
            if (summary == null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    Text(
                        stringResource(R.string.transfer_backups_unreadable),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Text(
                    pluralStringResource(R.plurals.transfer_backups_boxes, summary.inCabinet, summary.inCabinet),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (entry.summary != null) {
            // Every row has a Restore button: TalkBack says which backup each one restores.
            val description = stringResource(R.string.transfer_backups_restoreTitle, date)
            Button(
                onClick = { onRestore(entry) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }
                    .testTag("backups.restore")
            ) { Text(stringResource(R.string.transfer_backups_restore)) }
        }
    }
}

private val BackupReason.label: Int
    get() = when (this) {
        BackupReason.BEFORE_IMPORT -> R.string.transfer_backups_beforeImport
        BackupReason.BEFORE_RESTORE -> R.string.transfer_backups_beforeRestore
    }
