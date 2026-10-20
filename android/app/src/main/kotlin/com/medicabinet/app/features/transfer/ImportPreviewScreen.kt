@file:OptIn(ExperimentalMaterial3Api::class)

package com.medicabinet.app.features.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.core.archive.CabinetSummary
import com.medicabinet.core.archive.ImportPreview
import java.time.ZoneId

/**
 * The import (or restore) flow on one screen: progress, the preview with the confirmation, the result, the errors.
 * [onClose] leaves it; it is refused while the cabinet is being replaced, so the replace always finishes on screen.
 */
@Composable
fun ImportScreen(
    model: ImportViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val state by model.state.collectAsState()
    val applying = state is ImportState.Applying
    BackHandler(enabled = applying) {}
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(importTitle(model.request, zone)) },
                navigationIcon = {
                    if (!applying && state !is ImportState.Done) {
                        IconButton(onClick = onClose, modifier = Modifier.testTag("import.close")) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.transfer_import_cancel)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val current = state) {
                ImportState.Reading -> ImportProgress(stringResource(R.string.transfer_import_reading))
                ImportState.Checking -> ImportProgress(stringResource(R.string.transfer_import_checking))
                is ImportState.Applying -> ImportProgress(
                    stringResource(
                        if (current.backingUp) R.string.transfer_import_backingUp else R.string.transfer_import_applying
                    )
                )
                is ImportState.Preview -> ImportPreviewContent(
                    current.preview,
                    zone,
                    onReplace = model::replace,
                    onCancel = onClose
                )
                is ImportState.Done -> ImportResultContent(current.result, onClose)
                is ImportState.Failed -> ImportErrorContent(current.failure, onRetry = model::retry, onClose = onClose)
            }
        }
    }
    (state as? ImportState.Preview)?.takeIf { it.confirming }?.let {
        ReplaceConfirmation(onReplace = model::confirm, onCancel = model::dismissConfirmation)
    }
}

@Composable
private fun importTitle(request: ImportRequest, zone: ZoneId): String = when (request) {
    is ImportRequest.File -> stringResource(R.string.transfer_import_title)
    is ImportRequest.Backup -> stringResource(
        R.string.transfer_backups_restoreTitle,
        TransferText.dateTime(request.takenAt, LocalConfiguration.current.locales[0], zone)
    )
}

/** A step in progress; TalkBack reads each new step. */
@Composable
private fun ImportProgress(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator()
        Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("import.progress"))
    }
}

/** I1: the file against the phone, the replace statement, the backup statement, then the destructive button. */
@Composable
private fun ImportPreviewContent(preview: ImportPreview, zone: ZoneId, onReplace: () -> Unit, onCancel: () -> Unit) {
    SectionTitle(stringResource(R.string.transfer_import_preview_fileTitle))
    FileFacts(preview, zone)
    SectionTitle(stringResource(R.string.transfer_import_preview_currentTitle))
    CurrentCounts(preview.current)
    Statement(
        Icons.Outlined.WarningAmber,
        stringResource(R.string.transfer_import_preview_replaceStatement),
        "import.replaceStatement"
    )
    val (backupText, backupTag) = if (preview.willBackUp) {
        R.string.transfer_import_preview_backupStatement to "import.backupStatement"
    } else {
        R.string.transfer_import_preview_noBackupNeeded to "import.noBackupNeeded"
    }
    Statement(Icons.Outlined.Info, stringResource(backupText), backupTag)
    Button(
        onClick = onReplace,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("import.replace")
    ) { Text(stringResource(R.string.transfer_import_replace)) }
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("import.cancel")
    ) {
        Text(stringResource(R.string.transfer_import_cancel))
    }
}

/** When and by what the file was written, its format version (the original one, I8) and what it holds. */
@Composable
private fun FileFacts(preview: ImportPreview, zone: ZoneId) {
    val archive = preview.archive
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.testTag("import.file"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val exportedAt = TransferText.dateTime(archive.exportedAt, locale, zone)
        Text(stringResource(R.string.transfer_import_preview_exportedAt, exportedAt))
        val platform = TransferText.platform(archive.app.platform)
        Text(
            stringResource(R.string.transfer_import_preview_exportedBy, archive.app.name, archive.app.version, platform)
        )
        Text(stringResource(R.string.transfer_import_preview_formatVersion, archive.sourceFormatVersion))
        SummaryCounts(preview.file)
        val unknown = preview.file.unknownExpiry
        if (unknown > 0) Text(pluralStringResource(R.plurals.transfer_import_preview_unknownExpiry, unknown, unknown))
    }
}

/** What the phone holds now: boxes in the cabinet and locations. */
@Composable
private fun CurrentCounts(summary: CabinetSummary) {
    Column(Modifier.testTag("import.current"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(pluralStringResource(R.plurals.transfer_export_summary_boxes, summary.inCabinet, summary.inCabinet))
        Text(pluralStringResource(R.plurals.transfer_export_summary_locations, summary.locations, summary.locations))
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

/** A sentence with an icon: the meaning never rests on colour alone (X3). */
@Composable
private fun Statement(icon: ImageVector, text: String, tag: String) {
    Row(Modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** I2: a second, explicit question; **Cancel** changes nothing. */
@Composable
private fun ReplaceConfirmation(onReplace: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.transfer_import_confirm_title)) },
        text = { Text(stringResource(R.string.transfer_import_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = onReplace,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 48.dp).testTag("import.confirm.replace")
            ) { Text(stringResource(R.string.transfer_import_confirm_replace)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("import.confirm.cancel")) {
                Text(stringResource(R.string.transfer_import_cancel))
            }
        }
    )
}
