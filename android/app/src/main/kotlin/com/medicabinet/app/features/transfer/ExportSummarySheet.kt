@file:OptIn(ExperimentalMaterial3Api::class)

package com.medicabinet.app.features.transfer

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.core.archive.ArchiveFormat
import com.medicabinet.core.archive.CabinetSummary

/**
 * Everything the export shows on top of Settings: the summary sheet with the warning, progress, the error with
 * **Try again**, the system pickers, and the "File saved" confirmation (a snackbar).
 */
@Composable
fun ExportFlow(model: ExportViewModel) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val undo = LocalUndoController.current
    val saved = stringResource(R.string.transfer_export_saved)
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ArchiveFormat.MIME_TYPE)) {
            model.onDocumentChosen(it)
        }
    LaunchedEffect(model) {
        model.events.collect { event ->
            when (event) {
                is ExportEvent.CreateDocument -> createDocument.launch(event.fileName)
                is ExportEvent.Share -> try {
                    context.startActivity(ArchiveFiles.shareIntent(event.uri))
                } catch (@Suppress("SwallowedException") error: ActivityNotFoundException) {
                    model.onShareFailed()
                }
                ExportEvent.Saved -> undo?.notify(saved)
            }
        }
    }
    when (val current = state) {
        ExportState.Idle -> Unit
        ExportState.Summarizing -> TransferProgressDialog(stringResource(R.string.transfer_export_loading))
        ExportState.Writing -> TransferProgressDialog(stringResource(R.string.transfer_export_writing))
        is ExportState.Confirm -> ExportSummarySheet(
            current.summary,
            onSave = model::saveToFile,
            onShare = model::share,
            onCancel = model::cancel
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = model::cancel,
            title = { Text(stringResource(R.string.transfer_export_title)) },
            text = { Text(stringResource(R.string.transfer_export_failed), Modifier.testTag("export.failed")) },
            confirmButton = {
                TextButton(onClick = model::retry, modifier = Modifier.heightIn(min = 48.dp).testTag("export.retry")) {
                    Text(stringResource(R.string.transfer_export_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = model::cancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.transfer_export_cancel))
                }
            }
        )
    }
}

/**
 * What will leave the phone, and the warning that the file is readable health data (E2). **Cancel** exports nothing.
 */
@Composable
fun ExportSummarySheet(summary: CabinetSummary, onSave: () -> Unit, onShare: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).testTag("export.sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.transfer_export_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            SummaryCounts(summary)
            if (summary.inCabinet + summary.removed == 0) {
                Text(stringResource(R.string.transfer_export_summary_empty), Modifier.testTag("export.empty"))
            }
            HealthDataWarning()
            Column(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export.save")
                ) {
                    Text(stringResource(R.string.transfer_export_saveToFile))
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export.share")
                ) { Text(stringResource(R.string.transfer_export_share)) }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export.cancel")
                ) { Text(stringResource(R.string.transfer_export_cancel)) }
            }
        }
    }
}

/** Boxes in the cabinet, removed boxes and locations: the counts the export summary and the import show. */
@Composable
internal fun SummaryCounts(summary: CabinetSummary, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(pluralStringResource(R.plurals.transfer_export_summary_boxes, summary.inCabinet, summary.inCabinet))
        Text(pluralStringResource(R.plurals.transfer_export_summary_removed, summary.removed, summary.removed))
        Text(pluralStringResource(R.plurals.transfer_export_summary_locations, summary.locations, summary.locations))
    }
}

/** The warning carries an icon and words, never colour alone (X3). */
@Composable
private fun HealthDataWarning() {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp).testTag("export.warning"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
            Text(
                stringResource(R.string.transfer_export_warning),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** A step in progress that cannot be cancelled half-way; TalkBack reads the text when it appears. */
@Composable
internal fun TransferProgressDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("transfer.progress"))
            }
        }
    )
}
