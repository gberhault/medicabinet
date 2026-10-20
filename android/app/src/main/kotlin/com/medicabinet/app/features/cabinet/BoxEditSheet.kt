package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.app.features.CategoryLabels
import com.medicabinet.app.features.scan.ExpiryChoice
import com.medicabinet.app.features.scan.ExpiryEditor
import com.medicabinet.core.Box as CabinetBox
import com.medicabinet.core.CategoryID
import com.medicabinet.core.Location
import com.medicabinet.core.cabinet.BoxEdit
import com.medicabinet.core.cabinet.BoxEditError
import com.medicabinet.core.cabinet.BoxEditException
import com.medicabinet.core.cabinet.CategoryChange
import com.medicabinet.core.reference.Taxonomy
import kotlinx.coroutines.launch

/** What is wrong with the form (the name), or `null` when the name can be saved. */
internal fun BoxEdit.nameProblem(box: CabinetBox): BoxEditError? = try {
    applyTo(box)
    null
} catch (error: BoxEditException) {
    error.error.takeIf { it == BoxEditError.EMPTY_NAME || it == BoxEditError.NAME_TOO_LONG }
}

/** The category the form currently stands on, and whether the user chose it. */
private fun BoxEdit.category(box: CabinetBox): Pair<CategoryID, Boolean> = when (val change = categoryChange) {
    is CategoryChange.Chosen -> change.category to true
    is CategoryChange.Automatic -> change.category to false
    null -> box.category to box.categoryOverridden
}

/**
 * The edit form (B2, B4): name, expiry (the #12 field: month, year, optional day, or **Date not printed** — never
 * pre-filled when there was no date), location and category. The code, lot and serial are not editable. **Save** stores
 * the changes, **Cancel** discards them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxEditSheet(model: BoxDetailViewModel, state: BoxDetailState.Loaded, onDismiss: () -> Unit) {
    val box = state.box
    var edit by remember { mutableStateOf(BoxEdit.from(box)) }
    var expiry by remember { mutableStateOf(ExpiryChoice.of(box.expiry, editing = true)) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val problem = edit.nameProblem(box)
    val canSave = problem == null && expiry.resolved !is ExpiryChoice.Resolved.Pending
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("box.edit.sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.box_edit_title), style = MaterialTheme.typography.titleLarge)
            NameField(edit.name, problem) { edit = edit.copy(name = it) }
            ExpiryEditor(expiry, onChange = { expiry = it })
            LocationField(state.locations, edit.locationId) { edit = edit.copy(locationId = it) }
            CategoryField(box, edit, { edit = it }, model)
            if (saveFailed) ErrorLine(stringResource(R.string.box_error), "box.edit.error")
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("box.edit.cancel")
                ) { Text(stringResource(R.string.box_edit_cancel)) }
                Button(
                    onClick = {
                        scope.launch {
                            val result = model.save(edit.copy(expiry = expiry.expiry))
                            if (result is SaveResult.Saved) onDismiss() else saveFailed = true
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("box.edit.save")
                ) { Text(stringResource(R.string.box_edit_save)) }
            }
        }
    }
}

@Composable
private fun NameField(name: String, problem: BoxEditError?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.box_edit_name)) },
        isError = problem != null,
        supportingText = {
            problem?.let {
                val message = if (it ==
                    BoxEditError.EMPTY_NAME
                ) {
                    R.string.box_edit_nameEmpty
                } else {
                    R.string.box_edit_nameTooLong
                }
                Text(stringResource(message), Modifier.testTag("box.edit.nameError"))
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("box.edit.name")
    )
}

@Composable
private fun LocationField(locations: List<Location>, selected: java.util.UUID, onSelect: (java.util.UUID) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.box_location)
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("box.edit.location")
        ) { Text("$label: ${locations.firstOrNull { it.id == selected }?.name.orEmpty()}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            locations.forEach { location ->
                DropdownMenuItem(
                    text = { Text(location.name) },
                    onClick = {
                        onSelect(location.id)
                        open = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
    }
}

/**
 * Category picker (B4): the taxonomy's user-selectable categories, a "Set by you" marker once chosen, and **Automatic
 * category** to give the box back to the classifier. Categories tidy the cabinet; they never advise a treatment.
 */
@Composable
private fun CategoryField(box: CabinetBox, edit: BoxEdit, onChange: (BoxEdit) -> Unit, model: BoxDetailViewModel) {
    val (category, manual) = edit.category(box)
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.box_category),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(stringResource(CategoryLabels.resource(category)), Modifier.testTag("box.edit.category"))
        if (manual) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.box_category_manual), style = MaterialTheme.typography.bodySmall)
            }
        }
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("box.edit.category.choose")
            ) { Text(stringResource(R.string.box_category_choose)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Taxonomy.userSelectable.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(CategoryLabels.resource(option))) },
                        trailingIcon = {
                            if (option == category) Icon(Icons.Outlined.Check, contentDescription = null)
                        },
                        onClick = {
                            onChange(edit.chooseCategory(option))
                            open = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("box.edit.category.${option.rawValue}")
                    )
                }
            }
        }
        OutlinedButton(
            onClick = { scope.launch { onChange(edit.resetCategory(model.automaticCategory())) } },
            enabled = manual,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("box.edit.category.automatic")
        ) { Text(stringResource(R.string.box_category_automatic)) }
        Text(
            stringResource(R.string.box_category_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorLine(message: String, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag))
    }
}
