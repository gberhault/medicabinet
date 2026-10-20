package com.medicabinet.app.features.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import java.time.Month
import java.time.format.TextStyle

private const val YEAR_DIGITS = 4
private const val DAYS_IN_MONTH_MAX = 31
private const val MONTHS = 12

/** Month, year and optional day, plus "Date not printed". Nothing is preselected: a date is never guessed. */
@Composable
fun ExpiryEditor(
    choice: ExpiryChoice,
    onChange: (ExpiryChoice) -> Unit,
    modifier: Modifier = Modifier,
    showsUnreadableWarning: Boolean = false
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showsUnreadableWarning) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Warning, contentDescription = null)
                Text(
                    stringResource(R.string.scan_complete_expiryUnreadable),
                    modifier = Modifier.testTag("expiry.unreadable")
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(choice.notPrinted, role = Role.Switch) { onChange(choice.copy(notPrinted = it)) }
                .testTag("expiry.notPrinted"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.scan_complete_dateNotPrinted), Modifier.weight(1f))
            Switch(checked = choice.notPrinted, onCheckedChange = null)
        }
        if (!choice.notPrinted) {
            val input = choice.input
            MonthPicker(input.month) { onChange(choice.copy(input = input.copy(month = it))) }
            OutlinedTextField(
                value = input.year?.toString().orEmpty(),
                onValueChange = { text ->
                    val digits = text.filter(Char::isDigit).take(YEAR_DIGITS)
                    onChange(choice.copy(input = input.copy(year = digits.toIntOrNull())))
                },
                label = { Text(stringResource(R.string.expiry_field_year)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("expiry.year")
            )
            DayPicker(input.day) { onChange(choice.copy(input = input.copy(day = it))) }
            if (input.outcome == ExpiryInput.Outcome.Invalid) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        stringResource(R.string.expiry_field_invalid),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("expiry.invalid")
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthPicker(month: Int?, onSelect: (Int?) -> Unit) {
    val locale = LocalContext.current.resources.configuration.locales[0]
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.expiry_field_month)
    OutlinedButton(onClick = {
        open = true
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("expiry.month")) {
        Text("$label: ${month?.let { Month.of(it).getDisplayName(TextStyle.FULL_STANDALONE, locale) } ?: "—"}")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("—") }, onClick = {
            onSelect(null)
            open = false
        })
        for (number in 1..MONTHS) {
            DropdownMenuItem(
                text = { Text(Month.of(number).getDisplayName(TextStyle.FULL_STANDALONE, locale)) },
                onClick = {
                    onSelect(number)
                    open = false
                },
                modifier = Modifier.testTag("expiry.month.$number")
            )
        }
    }
}

@Composable
private fun DayPicker(day: Int?, onSelect: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.expiry_field_day)
    val none = stringResource(R.string.expiry_field_noDay)
    OutlinedButton(onClick = {
        open = true
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("expiry.day")) {
        Text("$label: ${day?.toString() ?: none}")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text(none) }, onClick = {
            onSelect(null)
            open = false
        })
        for (number in 1..DAYS_IN_MONTH_MAX) {
            DropdownMenuItem(
                text = { Text(number.toString()) },
                onClick = {
                    onSelect(number)
                    open = false
                },
                modifier = Modifier.testTag("expiry.day.$number")
            )
        }
    }
}
