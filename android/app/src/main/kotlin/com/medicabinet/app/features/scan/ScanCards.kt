package com.medicabinet.app.features.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.core.Box
import kotlinx.coroutines.delay

private const val NOTICE_MILLIS = 6_000L

/** The actions a [NoticeCard] can trigger. */
class NoticeActions(
    val onUndoAdd: (Box) -> Unit,
    val onPutBack: (Box) -> Unit,
    val onUndoDelete: (Box) -> Unit,
    val onDismiss: () -> Unit
)

/** The confirmation (or refusal) of the last scan. Text and icon carry the meaning, not colour alone. */
@Composable
fun NoticeCard(notice: Notice, actions: NoticeActions, modifier: Modifier = Modifier) {
    LaunchedEffect(notice) {
        delay(NOTICE_MILLIS)
        actions.onDismiss()
    }
    Card(
        modifier = modifier.fillMaxWidth().testTag("scan.notice").semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NoticeContent(notice, actions)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NoticeContent(notice: Notice, actions: NoticeActions) {
    val context = LocalContext.current
    when (notice) {
        is Notice.Added -> {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(notice.box.product.name, style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        if (notice.box.expiry == null) Icons.Outlined.Warning else Icons.Outlined.CalendarMonth,
                        contentDescription = null
                    )
                    Text(ScanText.expiry(context, notice.box.expiry), style = MaterialTheme.typography.bodySmall)
                }
            }
            CardAction(R.string.scan_undo, "scan.notice.undo") { actions.onUndoAdd(notice.box) }
        }
        is Notice.AlreadyInCabinet -> {
            Icon(Icons.Outlined.Info, contentDescription = null)
            Text(stringResource(R.string.scan_duplicate_inCabinet, notice.locationName), Modifier.weight(1f))
        }
        is Notice.RemovedEarlier -> {
            Icon(Icons.Outlined.Info, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(notice.box.product.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.scan_duplicate_removedEarlier), style = MaterialTheme.typography.bodySmall)
            }
            CardAction(R.string.scan_duplicate_putBack, "scan.notice.putBack") { actions.onPutBack(notice.box) }
        }
        is Notice.PutBack -> {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(notice.box.product.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.scan_putBack_done), style = MaterialTheme.typography.bodySmall)
            }
        }
        is Notice.Deleted -> {
            Icon(Icons.Outlined.Delete, contentDescription = null)
            Text(stringResource(R.string.scan_session_deleted), Modifier.weight(1f))
            CardAction(R.string.scan_undo, "scan.notice.undoDelete") { actions.onUndoDelete(notice.box) }
        }
        Notice.Unrecognized -> {
            Icon(Icons.Outlined.HelpOutline, contentDescription = null)
            Text(stringResource(R.string.scan_unrecognized), Modifier.weight(1f))
        }
        Notice.Failed -> {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.scan_error_generic), Modifier.weight(1f))
        }
    }
}

@Composable
private fun CardAction(label: Int, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
        Text(stringResource(label))
    }
}

/** "Another box of X?": does not stop the camera; the next scan dismisses it. */
@Composable
fun AnotherCard(another: Another, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("scan.another"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Outlined.AddCircle, contentDescription = null)
            Text(
                stringResource(R.string.scan_another_question, another.draft.name ?: another.draft.gtin.orEmpty()),
                Modifier.weight(1f)
            )
            Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp).testTag("scan.another.add")) {
                Text(stringResource(R.string.scan_another_add))
            }
        }
    }
}
