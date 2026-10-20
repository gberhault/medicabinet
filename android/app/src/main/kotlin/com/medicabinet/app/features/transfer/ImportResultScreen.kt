package com.medicabinet.app.features.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.core.archive.ImportResult

/** I7: the imported cabinet in numbers, and where the previous one was kept. */
@Composable
internal fun ImportResultContent(result: ImportResult, onClose: () -> Unit) {
    Headline(Icons.Outlined.CheckCircle, stringResource(R.string.transfer_import_done), "import.done")
    SummaryCounts(result.imported)
    if (result.backup !=
        null
    ) {
        Text(stringResource(R.string.transfer_import_done_backupKept), Modifier.testTag("import.backupKept"))
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("import.doneClose")) {
        Text(stringResource(R.string.transfer_import_done_close))
    }
}

/** The file was refused or the import failed; the message says what, and that the cabinet is unchanged. */
@Composable
internal fun ImportErrorContent(failure: ImportFailure, onRetry: () -> Unit, onClose: () -> Unit) {
    Headline(Icons.Outlined.ErrorOutline, stringResource(R.string.transfer_error_title), "import.errorTitle")
    Text(stringResource(failure.message), Modifier.testTag("import.errorMessage"))
    if (failure.canRetry) {
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("import.retry")) {
            Text(stringResource(R.string.transfer_error_retry))
        }
    }
    OutlinedButton(
        onClick = onClose,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("import.errorClose")
    ) {
        Text(stringResource(R.string.transfer_error_close))
    }
}

/** A result announced by TalkBack as soon as it appears. */
@Composable
private fun Headline(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tag: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null)
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            }.testTag(tag)
        )
    }
}

/** The message of each failure (strings.md, "Errors"). */
internal val ImportFailure.message: Int
    get() = when (this) {
        ImportFailure.TOO_LARGE -> R.string.transfer_error_tooLarge
        ImportFailure.NOT_ARCHIVE -> R.string.transfer_error_notArchive
        ImportFailure.DAMAGED -> R.string.transfer_error_damaged
        ImportFailure.NEWER_VERSION -> R.string.transfer_error_newerVersion
        ImportFailure.READ_FAILED -> R.string.transfer_error_readFailed
        ImportFailure.BACKUP_FAILED -> R.string.transfer_error_backupFailed
        ImportFailure.REPLACE_FAILED -> R.string.transfer_error_replaceFailed
    }
