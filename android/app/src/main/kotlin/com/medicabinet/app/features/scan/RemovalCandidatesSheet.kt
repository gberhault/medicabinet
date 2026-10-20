package com.medicabinet.app.features.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.core.Box
import com.medicabinet.core.Location
import java.util.UUID

/**
 * Several boxes match the scan: the user picks the one taken out (R7). The first is pre-selected; Cancel (or
 * dismissing the sheet) removes nothing. The camera ignores recognitions while this is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemovalCandidatesSheet(
    candidates: Candidates,
    locations: List<Location>,
    onRemove: (UUID) -> Unit,
    onCancel: () -> Unit
) {
    var selected by remember(candidates.id) { mutableStateOf(candidates.boxes.firstOrNull()?.id) }
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.padding(horizontal = 16.dp).testTag("candidates.sheet")) {
            Text(stringResource(R.string.scan_removal_choose_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.scan_removal_choose_message), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(candidates.boxes, key = { it.id }) { box ->
                    CandidateRow(box, locations, selected == box.id) { selected = box.id }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("candidate.cancel")
                ) { Text(stringResource(R.string.scan_removal_choose_cancel)) }
                Button(
                    onClick = { selected?.let(onRemove) },
                    enabled = selected != null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("candidate.remove")
                ) { Text(stringResource(R.string.scan_removal_choose_remove)) }
            }
        }
    }
}

@Composable
private fun CandidateRow(box: Box, locations: List<Location>, selected: Boolean, onSelect: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("candidate.row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(box.product.name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    if (box.expiry == null) Icons.Outlined.Warning else Icons.Outlined.CalendarMonth,
                    contentDescription = null
                )
                Text(ScanText.expiry(context, box.expiry), style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Place, contentDescription = null)
                Text(
                    locations.firstOrNull { it.id == box.locationId }?.name.orEmpty(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (box.isOpened) {
                Text(stringResource(R.string.scan_removal_choose_opened), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
