package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.Location
import com.medicabinet.core.cabinet.CabinetFilter
import java.util.UUID

private val STATUSES = listOf(
    ExpiryStatus.EXPIRED,
    ExpiryStatus.EXPIRING_SOON,
    ExpiryStatus.UNKNOWN,
    ExpiryStatus.OK
)

/**
 * The location and status menus, then the active filters as text with a one-tap **Clear**. Each menu is a button that
 * shows its label above its current value (so the choice is readable, not colour-coded).
 */
@Composable
fun CabinetFilterBar(
    filter: CabinetFilter,
    locations: List<Location>,
    onLocation: (UUID?) -> Unit,
    onStatus: (ExpiryStatus?) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val locationName = locations.firstOrNull { it.id == filter.locationId }?.name
    val statusName = filter.status?.let { stringResource(expiryStatusText(it)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocationMenu(locations, locationName, onLocation, Modifier.weight(1f))
            StatusMenu(statusName, onStatus, Modifier.weight(1f))
        }
        if (filter.isActive) {
            val parts = listOfNotNull(filter.query.trim().takeIf { it.isNotEmpty() }, locationName, statusName)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.cabinet_filter_active, parts.joinToString(", ")),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).testTag("cabinet.filter.active")
                )
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("cabinet.filter.clear")
                ) { Text(stringResource(R.string.cabinet_filter_clear)) }
            }
        }
    }
}

@Composable
private fun LocationMenu(
    locations: List<Location>,
    selectedName: String?,
    onSelect: (UUID?) -> Unit,
    modifier: Modifier
) {
    val all = stringResource(R.string.cabinet_filter_allLocations)
    FilterMenu(
        label = stringResource(R.string.cabinet_filter_location),
        value = selectedName ?: all,
        tag = "cabinet.filter.location",
        modifier = modifier
    ) { close ->
        MenuItem(all, "cabinet.filter.location.all") {
            onSelect(null)
            close()
        }
        locations.forEach { location ->
            MenuItem(location.name, "cabinet.filter.location.${location.id}") {
                onSelect(location.id)
                close()
            }
        }
    }
}

@Composable
private fun StatusMenu(selectedName: String?, onSelect: (ExpiryStatus?) -> Unit, modifier: Modifier) {
    val all = stringResource(R.string.cabinet_filter_all)
    FilterMenu(
        label = stringResource(R.string.cabinet_filter_status),
        value = selectedName ?: all,
        tag = "cabinet.filter.status",
        modifier = modifier
    ) { close ->
        MenuItem(all, "cabinet.filter.status.all") {
            onSelect(null)
            close()
        }
        STATUSES.forEach { status ->
            MenuItem(stringResource(expiryStatusText(status)), "cabinet.filter.status.${status.rawValue}") {
                onSelect(status)
                close()
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag)
    )
}

@Composable
private fun FilterMenu(
    label: String,
    value: String,
    tag: String,
    modifier: Modifier = Modifier,
    items: @Composable (close: () -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}
