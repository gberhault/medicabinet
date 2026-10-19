package com.medicabinet.app.features.settings.locations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.core.Location
import java.util.UUID
import kotlinx.coroutines.launch

/** Create ([renaming] is `null`) or rename a location. The message under the field says why a name is refused. */
@Composable
fun LocationNameDialog(viewModel: LocationsViewModel, renaming: Location?, onDismiss: () -> Unit) {
    // The cursor starts at the end, so typing extends the current name instead of prefixing it.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val initial = renaming?.name.orEmpty()
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The dialog's field is attached one frame after the first composition.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    var problem by remember { mutableStateOf<NameProblem?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val refused = if (renaming == null) viewModel.add(field.text) else viewModel.rename(renaming, field.text)
            saving = false
            if (refused == null) onDismiss() else problem = refused
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (renaming ==
                        null
                    ) {
                        R.string.locations_name_addTitle
                    } else {
                        R.string.locations_name_renameTitle
                    }
                )
            )
        },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    problem = null
                },
                label = { Text(stringResource(R.string.locations_name_field)) },
                singleLine = true,
                isError = problem != null,
                supportingText = { problem?.let { ProblemMessage(it) } },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("locations.name.field")
            )
        },
        confirmButton = {
            TextButton(
                onClick = ::save,
                enabled = !saving && field.text.isNotBlank(),
                modifier = Modifier.testTag("locations.name.save")
            ) { Text(stringResource(R.string.locations_name_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.locations_name_cancel)) } }
    )
}

/** The message carries an icon and text, never colour alone. */
@Composable
private fun ProblemMessage(problem: NameProblem) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("locations.name.problem")) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
        Text(
            stringResource(
                when (problem) {
                    NameProblem.EMPTY -> R.string.locations_name_empty
                    NameProblem.TOO_LONG -> R.string.locations_name_tooLong
                    NameProblem.DUPLICATE -> R.string.locations_name_duplicate
                }
            ),
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/**
 * Deleting a location that still holds boxes: say how many and where they go (the default location is pre-selected).
 * Nothing changes until **Move and delete**.
 */
@Composable
fun DeleteLocationDialog(request: Deletion, onConfirm: (UUID) -> Unit, onDismiss: () -> Unit) {
    var destination by rememberSaveable(request.location.id) { mutableStateOf(request.suggestedDestination) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.locations_delete_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(pluralStringResource(R.plurals.locations_delete_message, request.boxCount, request.boxCount))
                Text(
                    stringResource(R.string.locations_delete_destination),
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                request.destinations.forEach { location ->
                    DestinationRow(location, selected = location.id == destination) { destination = location.id }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { destination?.let(onConfirm) },
                enabled = destination != null,
                modifier = Modifier.testTag("locations.delete.confirm")
            ) { Text(stringResource(R.string.locations_delete_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.locations_name_cancel)) } }
    )
}

@Composable
private fun DestinationRow(location: Location, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(location.name, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A write failed and nothing was changed. */
@Composable
fun ChangeFailedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.locations_changeFailed_title)) },
        text = { Text(stringResource(R.string.locations_changeFailed_message)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.locations_changeFailed_ok)) } }
    )
}
