package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicabinet.app.R
import com.medicabinet.app.features.CategoryLabels
import com.medicabinet.app.features.scan.ScanText
import com.medicabinet.core.cabinet.CategorySection
import com.medicabinet.core.cabinet.ProductRow

/** The Cabinet tab: what the household has, by category, searchable and filterable by location and expiry status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CabinetScreen(
    model: CabinetViewModel,
    onOpenProduct: (ProductRow) -> Unit,
    onOpenScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.onForeground() }
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.cabinet_title)) })
    }) { padding ->
        val content = Modifier.padding(padding).fillMaxSize()
        when (val current = state) {
            CabinetUiState.Loading -> LoadingMessage(content)
            CabinetUiState.Failed -> ErrorMessage(content, model::retry)
            is CabinetUiState.Loaded ->
                if (current.isCabinetEmpty) {
                    EmptyMessage(content, onOpenScan)
                } else {
                    CabinetList(model, current, onOpenProduct, content)
                }
        }
    }
}

@Composable
private fun CabinetList(
    model: CabinetViewModel,
    state: CabinetUiState.Loaded,
    onOpenProduct: (ProductRow) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier.testTag("cabinet.list"), contentPadding = PaddingValues(16.dp)) {
        item(key = "search") { SearchField(model, Modifier.padding(bottom = 8.dp)) }
        item(key = "filters") {
            CabinetFilterBar(
                state.filter,
                state.locations,
                onLocation = model::setLocation,
                onStatus = model::setStatus,
                onClear = model::clearFilters,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (state.snapshot.sections.isEmpty()) {
            item(key = "no-results") { NoResults(model::clearFilters) }
        } else {
            item(key = "count") {
                Text(
                    pluralStringResource(R.plurals.cabinet_count, state.snapshot.boxCount, state.snapshot.boxCount),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 8.dp).semantics { heading() }.testTag("cabinet.count")
                )
            }
            state.snapshot.sections.forEach { section -> sectionItems(section, onOpenProduct) }
        }
    }
}

private fun LazyListScope.sectionItems(section: CategorySection, onOpenProduct: (ProductRow) -> Unit) {
    item(key = "category:${section.category.rawValue}") {
        Text(
            stringResource(CategoryLabels.resource(section.category)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() }.testTag("cabinet.category")
        )
    }
    items(section.rows, key = { CabinetRoutes.rowKey(it.id) }) { row ->
        ProductRowItem(row) { onOpenProduct(row) }
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
        label = { Text(stringResource(R.string.cabinet_search_prompt)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = {
                    text = ""
                    model.setQuery("")
                }) { Icon(Icons.Outlined.Close, stringResource(R.string.cabinet_noResults_clear)) }
            }
        },
        singleLine = true,
        modifier = modifier.fillMaxWidth().testTag("cabinet.search")
    )
}

/** One product: name, number of boxes, earliest known expiry and the most urgent status (text and icon). */
@Composable
private fun ProductRowItem(row: ProductRow, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag("cabinet.row"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(row.name, style = MaterialTheme.typography.titleMedium)
        Text(
            pluralStringResource(R.plurals.cabinet_row_boxes, row.boxes.size, row.boxes.size),
            style = MaterialTheme.typography.bodyMedium
        )
        // An unknown expiry is said once, by the status label.
        row.earliestExpiry?.let { Text(ScanText.expiry(context, it), style = MaterialTheme.typography.bodyMedium) }
        ExpiryStatusLabel(row.status)
    }
}

@Composable
private fun LoadingMessage(modifier: Modifier) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.cabinet_loading))
    }
}

@Composable
private fun ErrorMessage(modifier: Modifier, onRetry: () -> Unit) {
    CenteredMessage(
        modifier,
        Icons.Outlined.ErrorOutline,
        stringResource(R.string.cabinet_error_title),
        stringResource(R.string.cabinet_error_message)
    ) {
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("cabinet.retry")) {
            Text(stringResource(R.string.cabinet_error_retry))
        }
    }
}

@Composable
private fun EmptyMessage(modifier: Modifier, onOpenScan: () -> Unit) {
    CenteredMessage(
        modifier,
        Icons.Outlined.MedicalServices,
        stringResource(R.string.cabinet_empty_title),
        stringResource(R.string.cabinet_empty_message)
    ) {
        Button(onClick = onOpenScan, modifier = Modifier.heightIn(min = 48.dp).testTag("cabinet.empty.scan")) {
            Text(stringResource(R.string.cabinet_empty_scan))
        }
    }
}

@Composable
private fun NoResults(onClear: () -> Unit) {
    CenteredMessage(
        Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("cabinet.noResults"),
        Icons.Outlined.SearchOff,
        stringResource(R.string.cabinet_noResults_title),
        stringResource(R.string.cabinet_noResults_message),
        scrolls = false
    ) {
        OutlinedButton(
            onClick = onClear,
            modifier = Modifier.heightIn(min = 48.dp).testTag("cabinet.noResults.clear")
        ) {
            Text(stringResource(R.string.cabinet_noResults_clear))
        }
    }
}

/**
 * Icon, title, message and one action, centred. A full-screen message [scrolls] so it stays reachable at 200 % font
 * scale; inside the list it does not (the list scrolls).
 */
@Composable
internal fun CenteredMessage(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    message: String,
    scrolls: Boolean = true,
    action: @Composable () -> Unit
) {
    Column(
        (if (scrolls) modifier.verticalScroll(rememberScrollState()) else modifier).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Row { action() }
    }
}
