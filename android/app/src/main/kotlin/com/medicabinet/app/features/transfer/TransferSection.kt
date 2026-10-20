package com.medicabinet.app.features.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medicabinet.app.R
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.app.BootstrapUiState
import com.medicabinet.app.persistence.AppPaths
import com.medicabinet.core.archive.ArchiveBackupEntry
import com.medicabinet.core.archive.ArchiveFormat
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers

/** The section with its view models, built from the environment (absent when the environment has no transfer). */
@Composable
fun TransferSettings(environment: AppEnvironment, onOpenBackups: () -> Unit = {}) {
    val transfer = environment.transfer ?: return
    val context = LocalContext.current.applicationContext
    val export = viewModel {
        ExportViewModel(
            transfer,
            ArchiveFiles(context, AppPaths(context).exportDirectory),
            environment.clock,
            { ZoneId.systemDefault() },
            Dispatchers.IO
        )
    }
    val coordinator = LocalImportCoordinator.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coordinator?.receive(uri)
    }
    TransferSection(export, onImport = { pick.launch(PICKER_TYPES) }, onBackups = onOpenBackups)
}

/**
 * What the in-app picker lists (§1.4): our type, and the types a file whose extension was changed in transit may have
 * come back with. The picker only lists; validation decides.
 */
private val PICKER_TYPES = arrayOf(ArchiveFormat.MIME_TYPE, "application/octet-stream", "application/json")

/** Opens the import flow for a waiting file once nothing holds it back (O4). */
@Composable
fun OpenIncomingImport(coordinator: ImportCoordinator, environment: AppEnvironment, open: () -> Unit) {
    val incoming by coordinator.incoming.collectAsState()
    val busy by coordinator.busy.collectAsState()
    LaunchedEffect(incoming, busy) {
        if (coordinator.gate(BootstrapUiState.Ready(environment)) == ImportGate.OPEN) open()
    }
}

/** Settings → **Automatic backups**, reloaded after every replace (a restore adds a backup). */
@Composable
fun BackupsRoute(environment: AppEnvironment, onRestore: (ArchiveBackupEntry) -> Unit, onBack: () -> Unit) {
    val transfer = environment.transfer ?: return
    val model = viewModel { BackupsViewModel(transfer, Dispatchers.IO) }
    val revision by environment.revision.collectAsState()
    BackupsScreen(model, revision, onRestore, onBack)
}

/** The import (or restore) flow as a screen of the Settings tab. It holds any newer file back until it closes. */
@Composable
fun ImportRoute(environment: AppEnvironment, request: () -> ImportRequest, onClose: () -> Unit) {
    val transfer = environment.transfer ?: return
    val afterReplace = LocalAfterCabinetReplaced.current
    val model = viewModel {
        ImportViewModel(transfer, request(), environment::cabinetReplaced, Dispatchers.IO, afterReplace)
    }
    HoldsUnsavedInput(true)
    ImportScreen(model, onClose)
}

/** Settings → **Backup and transfer** (E1, O3, B4): export the cabinet, import one, the automatic backups. */
@Composable
fun TransferSection(
    export: ExportViewModel,
    modifier: Modifier = Modifier,
    onImport: () -> Unit = {},
    onBackups: () -> Unit = {}
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.transfer_section_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.transfer_export_action)) },
            leadingContent = { Icon(Icons.Outlined.Upload, contentDescription = null) },
            modifier = Modifier.clickable(role = Role.Button, onClick = export::start).testTag("transfer.export")
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.transfer_import_action)) },
            // Where to go when a file manager did not offer MediCabinet (the extension was lost, §7.3).
            supportingContent = { Text(stringResource(R.string.transfer_import_wrongFileHint)) },
            leadingContent = { Icon(Icons.Outlined.Download, contentDescription = null) },
            modifier = Modifier.clickable(role = Role.Button, onClick = onImport).testTag("transfer.import")
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.transfer_backups_action)) },
            leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
            modifier = Modifier.clickable(role = Role.Button, onClick = onBackups).testTag("transfer.backups")
        )
        Text(
            stringResource(R.string.transfer_section_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
    ExportFlow(export)
}
