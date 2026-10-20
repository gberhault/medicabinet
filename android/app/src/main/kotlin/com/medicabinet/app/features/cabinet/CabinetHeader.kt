package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicabinet.app.R
import com.medicabinet.core.ExpiryStatus

private val STATUSES = listOf(
    ExpiryStatus.EXPIRED,
    ExpiryStatus.EXPIRING_SOON,
    ExpiryStatus.UNKNOWN,
    ExpiryStatus.OK
)

/**
 * The Cabinet's header: the title with the box count, the search field with the filter menu on its right, and one
 * removable chip per active filter (nothing when none is on). [state] is `null` until the cabinet is loaded and not
 * empty: the title alone shows in the loading, error and empty states.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CabinetHeader(model: CabinetViewModel, state: CabinetUiState.Loaded?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.cabinet_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() }
            )
            if (state != null) {
                Text(
                    pluralStringResource(R.plurals.cabinet_count, state.snapshot.boxCount, state.snapshot.boxCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Bottom).padding(bottom = 4.dp).testTag("cabinet.count")
                )
            }
        }
        if (state != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SearchField(model, Modifier.weight(1f))
                FilterMenu(model, state)
            }
            if (state.activeFilterCount > 0) FilterChips(model, state)
        }
    }
}

@Composable
private fun SearchField(model: CabinetViewModel, modifier: Modifier) {
    val query by model.query.collectAsStateWithLifecycle()
    // The field keeps its own text so typing never waits for the flow; it follows the model only when it is cleared.
    var text by remember { mutableStateOf(query) }
    LaunchedEffect(query) { if (query != text) text = query }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            model.setQuery(it)
        },
        placeholder = { Text(stringResource(R.string.cabinet_search_prompt)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = {
                    text = ""
                    model.setQuery("")
                }) { Icon(Icons.Outlined.Close, stringResource(R.string.cabinet_search_clear)) }
            }
        },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search
        ),
        modifier = modifier.testTag("cabinet.search")
    )
}

@Composable
private fun FilterMenu(model: CabinetViewModel, state: CabinetUiState.Loaded) {
    var open by remember { mutableStateOf(false) }
    val active = state.activeFilterCount
    val activeText = if (active >
        0
    ) {
        pluralStringResource(R.plurals.cabinet_filter_activeCount, active, active)
    } else {
        null
    }
    Box {
        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
            FilledTonalIconButton(
                onClick = { open = true },
                modifier = Modifier.size(48.dp).testTag("cabinet.filter.button").semantics {
                    activeText?.let { stateDescription = it }
                }
            ) {
                Icon(Icons.Outlined.FilterList, stringResource(R.string.cabinet_filter_button))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeader(stringResource(R.string.cabinet_filter_location))
            LocationItems(model, state, close = { open = false })
            HorizontalDivider()
            MenuHeader(stringResource(R.string.cabinet_filter_status))
            StatusItems(model, state, close = { open = false })
            if (active > 0) {
                HorizontalDivider()
                MenuItem(stringResource(R.string.cabinet_filter_clear), "cabinet.filter.clear") {
                    model.setLocation(null)
                    model.setStatus(null)
                    open = false
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() }
    )
}

@Composable
private fun LocationItems(model: CabinetViewModel, state: CabinetUiState.Loaded, close: () -> Unit) {
    MenuItem(
        stringResource(R.string.cabinet_filter_allLocations),
        "cabinet.filter.location.all",
        selected = state.filter.locationId == null
    ) {
        model.setLocation(null)
        close()
    }
    state.locations.forEach { location ->
        MenuItem(
            location.name,
            "cabinet.filter.location.${location.id}",
            selected = state.filter.locationId == location.id
        ) {
            model.setLocation(location.id)
            close()
        }
    }
}

@Composable
private fun StatusItems(model: CabinetViewModel, state: CabinetUiState.Loaded, close: () -> Unit) {
    MenuItem(
        stringResource(R.string.cabinet_filter_all),
        "cabinet.filter.status.all",
        selected = state.filter.status == null
    ) {
        model.setStatus(null)
        close()
    }
    STATUSES.forEach { status ->
        MenuItem(
            stringResource(expiryStatusText(status)),
            "cabinet.filter.status.${status.rawValue}",
            selected = state.filter.status == status,
            trailing = status
        ) {
            model.setStatus(status)
            close()
        }
    }
}

/** A menu row: a check when it is the current choice, and the status icon after the text for a status. */
@Composable
private fun MenuItem(
    text: String,
    tag: String,
    selected: Boolean = false,
    trailing: ExpiryStatus? = null,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
        },
        trailingIcon = trailing?.let { { Icon(expiryStatusIcon(it), contentDescription = null) } },
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag)
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterChips(model: CabinetViewModel, state: CabinetUiState.Loaded) {
    val locationName = state.locations.firstOrNull { it.id == state.filter.locationId }?.name
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        locationName?.let { name ->
            FilterChip(name, icon = Icons.Outlined.LocationOn, onRemove = { model.setLocation(null) })
        }
        state.filter.status?.let { status ->
            FilterChip(
                stringResource(expiryStatusText(status)),
                icon = expiryStatusIcon(status),
                onRemove = { model.setStatus(null) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChip(text: String, icon: ImageVector, onRemove: () -> Unit) {
    val removeText = stringResource(R.string.cabinet_filter_remove, text)
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.testTag("cabinet.filter.chip").semantics { contentDescription = removeText }
    )
}
