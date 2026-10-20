package com.medicabinet.app.features.settings.namedproducts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.medicabinet.app.features.cabinet.BoxCode
import com.medicabinet.app.features.common.CategoryField
import com.medicabinet.core.NamedProductSummary
import com.medicabinet.core.named.NamedProductEdit
import com.medicabinet.core.named.NamedProductEditError
import kotlinx.coroutines.launch

/**
 * **Edit product**: the name and the category of a product the household named. They apply to every box of the code,
 * including used-up and thrown-away ones (the note says so). The code is shown, never edited.
 */
@Composable
fun NamedProductEditDialog(viewModel: NamedProductsViewModel, row: NamedProductSummary, onDismiss: () -> Unit) {
    var edit by remember(row.product.id) { mutableStateOf(NamedProductEdit.from(row.product)) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val problem = viewModel.nameProblem(edit)?.takeIf {
        it == NamedProductEditError.EMPTY_NAME || it == NamedProductEditError.NAME_TOO_LONG
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val outcome = viewModel.save(edit)
            saving = false
            when (outcome) {
                SaveOutcome.Saved -> onDismiss()
                is SaveOutcome.Invalid -> Unit
                SaveOutcome.Failed -> saveFailed = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.namedProducts_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = edit.name,
                    onValueChange = {
                        edit = edit.copy(name = it)
                        saveFailed = false
                    },
                    label = { Text(stringResource(R.string.box_edit_name)) },
                    isError = problem != null,
                    supportingText = { problem?.let { NameProblem(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("namedProducts.edit.name")
                )
                CodeLine(BoxCode.display(row.product.gtin).orEmpty())
                CategoryField(
                    category = edit.category,
                    manual = edit.categoryOverridden,
                    onChoose = { edit = edit.chooseCategory(it) },
                    onAutomatic = { edit = edit.resetCategory() },
                    tagPrefix = "namedProducts.edit.category"
                )
                Text(
                    stringResource(R.string.namedProducts_edit_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (saveFailed) SaveFailedLine()
            }
        },
        confirmButton = {
            TextButton(
                onClick = ::save,
                enabled = problem == null && !saving,
                modifier = Modifier.heightIn(min = 48.dp).testTag("namedProducts.edit.save")
            ) { Text(stringResource(R.string.box_edit_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("namedProducts.edit.cancel")
            ) { Text(stringResource(R.string.box_edit_cancel)) }
        }
    )
}

/** The message carries an icon and text, never colour alone. */
@Composable
private fun NameProblem(problem: NamedProductEditError) {
    val message = if (problem == NamedProductEditError.EMPTY_NAME) {
        R.string.box_edit_nameEmpty
    } else {
        R.string.box_edit_nameTooLong
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("namedProducts.edit.problem")) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
        Text(stringResource(message), modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun CodeLine(code: String) {
    Column(Modifier.fillMaxWidth().testTag("namedProducts.edit.code")) {
        Text(
            stringResource(R.string.box_code),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(code, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SaveFailedLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(
            stringResource(R.string.box_error),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("namedProducts.edit.error")
        )
    }
}
