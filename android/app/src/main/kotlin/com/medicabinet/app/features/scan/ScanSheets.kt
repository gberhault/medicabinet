@file:OptIn(ExperimentalMaterial3Api::class)

package com.medicabinet.app.features.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.core.Box
import com.medicabinet.core.Location
import com.medicabinet.core.scan.IntakeField
import java.util.UUID
import kotlinx.coroutines.launch

/** Asked right after a scan, while the box is still in hand: only what the code could not tell. */
@Composable
fun CompletionSheet(
    completion: Completion,
    onConfirm: (String?, com.medicabinet.core.ExpiryDate?) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember(completion.id) { mutableStateOf("") }
    var expiry by remember(completion.id) { mutableStateOf(ExpiryChoice()) }
    val asksName = IntakeField.NAME in completion.missing
    val asksExpiry = IntakeField.EXPIRY in completion.missing
    val canAdd = (!asksName || name.isNotBlank()) && (!asksExpiry || expiry.resolved != ExpiryChoice.Resolved.Pending)
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { false })
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).testTag("completion.sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.scan_complete_title), style = MaterialTheme.typography.titleLarge)
            completion.draft.gtin?.let { Text(stringResource(R.string.scan_complete_code, it)) }
            if (asksName) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.scan_complete_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("completion.name")
                )
            }
            if (asksExpiry) {
                Text(stringResource(R.string.scan_complete_expiry), style = MaterialTheme.typography.titleSmall)
                ExpiryEditor(expiry, { expiry = it }, showsUnreadableWarning = completion.expiryUnreadable)
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("completion.cancel")) {
                    Text(stringResource(R.string.scan_complete_cancel))
                }
                Button(
                    onClick = { onConfirm(if (asksName) name else null, expiry.expiry) },
                    enabled = canAdd,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("completion.add")
                ) { Text(stringResource(R.string.scan_complete_add)) }
            }
        }
    }
}

/** What was added since the scanner opened, newest first: tap to correct, the bin deletes (with Undo). */
@Composable
fun SessionListSheet(
    session: ScanSession,
    locations: List<Location>,
    notice: Notice?,
    onEdit: (Box, String, com.medicabinet.core.ExpiryDate?, UUID) -> Unit,
    onDelete: (Box) -> Unit,
    onUndoDelete: (Box) -> Unit,
    onClose: () -> Unit
) {
    var editing by remember { mutableStateOf<Box?>(null) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.padding(horizontal = 16.dp).testTag("session.sheet")) {
            Text(stringResource(R.string.scan_session_title), style = MaterialTheme.typography.titleLarge)
            if (session.count == 0) {
                Text(stringResource(R.string.scan_session_empty), Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(Modifier.testTag("session.list")) {
                    items(session.newestFirst, key = { it.id }) { box ->
                        SessionRow(box, locations.firstOrNull { it.id == box.locationId }?.name.orEmpty(), {
                            editing =
                                box
                        }, { onDelete(box) })
                    }
                }
            }
            if (notice is Notice.Deleted) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.scan_session_deleted), Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        onUndoDelete(notice.box)
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("session.undoDelete")) {
                        Text(stringResource(R.string.scan_undo))
                    }
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("session.close")) {
                Text(stringResource(R.string.scan_session_close))
            }
        }
    }
    editing?.let { box ->
        EditBoxDialog(box, locations, { name, expiry, location ->
            onEdit(box, name, expiry, location)
            editing = null
        }, {
            editing =
                null
        })
    }
}

@Composable
private fun SessionRow(box: Box, locationName: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val delete = stringResource(R.string.scan_session_delete)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(
                1f
            ).clickable(
                onClickLabel = stringResource(R.string.scan_session_edit),
                onClick = onEdit
            ).padding(vertical = 8.dp).testTag("session.row")
        ) {
            Text(box.product.name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    if (box.expiry ==
                        null
                    ) {
                        Icons.Outlined.Warning
                    } else {
                        Icons.Outlined.CalendarMonth
                    },
                    contentDescription = null
                )
                Text(ScanText.expiry(context, box.expiry), style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Place, contentDescription = null)
                Text(locationName, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.semantics {
                contentDescription = delete
            }.testTag("session.delete")
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = null)
        }
    }
}

@Composable
private fun EditBoxDialog(
    box: Box,
    locations: List<Location>,
    onSave: (String, com.medicabinet.core.ExpiryDate?, UUID) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember(box.id) { mutableStateOf(box.product.name) }
    var expiry by remember(box.id) { mutableStateOf(ExpiryChoice.of(box.expiry, editing = true)) }
    var locationId by remember(box.id) { mutableStateOf(box.locationId) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.scan_session_editTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name,
                    {
                        name = it
                    },
                    label = { Text(stringResource(R.string.scan_complete_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("edit.name")
                )
                LocationPicker(locations, locationId) { locationId = it }
                ExpiryEditor(expiry, { expiry = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, expiry.expiry, locationId) },
                enabled = expiry.resolved != ExpiryChoice.Resolved.Pending && name.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("edit.save")
            ) { Text(stringResource(R.string.scan_session_save)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.scan_complete_cancel))
            }
        }
    )
}

@Composable
internal fun LocationPicker(
    locations: List<Location>,
    selected: UUID?,
    modifier: Modifier = Modifier,
    onSelect: (UUID) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.manual_location)
    OutlinedButton(onClick = {
        open = true
    }, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("location.picker")) {
        Text("$label: ${locations.firstOrNull { it.id == selected }?.name.orEmpty()}")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        locations.forEach { location ->
            DropdownMenuItem(text = { Text(location.name) }, onClick = {
                onSelect(location.id)
                open = false
            })
        }
    }
}

/** Adds a box without scanning: no code, a damaged code, or no camera. Works without camera permission. */
@Composable
fun ManualEntrySheet(
    locations: List<Location>,
    defaultLocation: UUID?,
    onAdd: suspend (String, String, com.medicabinet.core.ExpiryDate?, UUID) -> ManualError?,
    onClose: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(ExpiryChoice()) }
    var locationId by remember { mutableStateOf(defaultLocation ?: locations.firstOrNull()?.id) }
    var error by remember { mutableStateOf<ManualError?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).testTag("manual.sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.manual_title), style = MaterialTheme.typography.titleLarge)
            ManualFields(code, name, error, {
                code = it
                error = null
            }, {
                name = it
                error = null
            })
            LocationPicker(locations, locationId) { locationId = it }
            Text(stringResource(R.string.scan_complete_expiry), style = MaterialTheme.typography.titleSmall)
            ExpiryEditor(expiry, { expiry = it })
            if (error ==
                ManualError.FAILED
            ) {
                Text(stringResource(R.string.scan_error_generic), color = MaterialTheme.colorScheme.error)
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.scan_complete_cancel))
                }
                Button(
                    onClick = {
                        val location = locationId ?: return@Button
                        scope.launch {
                            error = onAdd(code, name, expiry.expiry, location)
                            if (error == null) onClose()
                        }
                    },
                    enabled = expiry.resolved != ExpiryChoice.Resolved.Pending && locationId != null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("manual.add")
                ) { Text(stringResource(R.string.manual_add)) }
            }
        }
    }
}

@Composable
private fun ManualFields(
    code: String,
    name: String,
    error: ManualError?,
    onCode: (String) -> Unit,
    onName: (String) -> Unit
) {
    OutlinedTextField(
        code,
        onCode,
        label = { Text(stringResource(R.string.manual_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = error == ManualError.INVALID_CODE,
        supportingText = { if (error == ManualError.INVALID_CODE) Text(stringResource(R.string.manual_codeInvalid)) },
        modifier = Modifier.fillMaxWidth().testTag("manual.code")
    )
    OutlinedTextField(
        name,
        onName,
        label = { Text(stringResource(R.string.manual_name)) },
        singleLine = true,
        isError = error == ManualError.INVALID_NAME,
        supportingText = { if (error == ManualError.INVALID_NAME) Text(stringResource(R.string.manual_nameRequired)) },
        modifier = Modifier.fillMaxWidth().testTag("manual.name")
    )
}

/** Shown after **Done**: the boxes are already saved; this only tells how the intake went. */
@Composable
fun SummaryDialog(summary: Summary, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.scan_summary_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scan_summary_added, summary.added), Modifier.testTag("summary.added"))
                if (summary.unknownExpiry > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Warning, contentDescription = null)
                        Text(
                            stringResource(R.string.scan_summary_unknownExpiry, summary.unknownExpiry),
                            Modifier.testTag("summary.unknownExpiry")
                        )
                    }
                }
                Text(stringResource(R.string.scan_summary_message))
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("summary.close")) {
                Text(stringResource(R.string.scan_session_close))
            }
        }
    )
}
