package com.medicabinet.app.features.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.medicabinet.core.CategoryID
import com.medicabinet.core.reference.Taxonomy
import kotlinx.coroutines.launch

/**
 * Category picker (B4), shared by the box edit and the named-product edit: the current category, a "Set by you" marker
 * once the user chose it, the taxonomy's user-selectable categories and **Automatic category** to give the item back to
 * the classifier. Categories tidy the cabinet; they never advise a treatment.
 *
 * [manual]: the user chose [category] (it is not the classifier's). [tagPrefix] names the test tags:
 * `<prefix>`, `<prefix>.choose`, `<prefix>.automatic` and `<prefix>.<category id>`.
 */
@Composable
fun CategoryField(
    category: CategoryID,
    manual: Boolean,
    onChoose: (CategoryID) -> Unit,
    onAutomatic: suspend () -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.box_category),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(stringResource(CategoryLabels.resource(category)), Modifier.testTag(tagPrefix))
        if (manual) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.box_category_manual), style = MaterialTheme.typography.bodySmall)
            }
        }
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$tagPrefix.choose")
            ) { Text(stringResource(R.string.box_category_choose)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Taxonomy.userSelectable.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(CategoryLabels.resource(option))) },
                        trailingIcon = {
                            if (option == category) Icon(Icons.Outlined.Check, contentDescription = null)
                        },
                        onClick = {
                            onChoose(option)
                            open = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("$tagPrefix.${option.rawValue}")
                    )
                }
            }
        }
        OutlinedButton(
            onClick = { scope.launch { onAutomatic() } },
            enabled = manual,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$tagPrefix.automatic")
        ) { Text(stringResource(R.string.box_category_automatic)) }
        Text(
            stringResource(R.string.box_category_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
