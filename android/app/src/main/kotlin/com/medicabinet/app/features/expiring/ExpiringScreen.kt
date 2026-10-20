package com.medicabinet.app.features.expiring

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicabinet.app.R
import com.medicabinet.app.features.cabinet.CenteredMessage
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.core.Box
import com.medicabinet.core.expiry.ExpiringEntry
import kotlinx.coroutines.launch

/**
 * The Expiring tab: what is expired, what expires soon and what has no known date, so the cabinet can be sorted out
 * in one pass. [reminderCard] is the monthly-reminder offer (shown above the list while it applies).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpiringScreen(model: ExpiringViewModel, modifier: Modifier = Modifier, reminderCard: @Composable () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val onThrowAway = rememberThrowAway(model)
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.tab_expiring)) })
    }) { padding ->
        val content = Modifier.padding(padding).fillMaxSize()
        when (val current = state) {
            ExpiringUiState.Loading -> LoadingMessage(content)
            ExpiringUiState.Failed -> ErrorMessage(content, model::retry)
            is ExpiringUiState.Empty -> EmptyMessage(current, content, reminderCard)
            is ExpiringUiState.Content -> ExpiringList(current, onThrowAway, reminderCard, content)
        }
    }
}

/** **Thrown away** then **Undo**; a failed write says so and leaves the box as it was (E9). */
@Composable
private fun rememberThrowAway(model: ExpiringViewModel): (Box) -> Unit {
    val scope = rememberCoroutineScope()
    val undoController = LocalUndoController.current
    val context = LocalContext.current
    val undoLabel = stringResource(R.string.undo_action)
    val failed = stringResource(R.string.expiring_thrownAway_failed)
    val undoFailed = stringResource(R.string.box_error)
    return { box ->
        scope.launch {
            if (model.throwAway(box)) {
                val message = context.getString(R.string.expiring_thrownAway_done, box.product.name)
                undoController?.show(message, undoLabel) {
                    if (!model.undoThrowAway(box.id)) undoController.notify(undoFailed)
                }
            } else {
                undoController?.notify(failed)
            }
        }
    }
}

@Composable
private fun ExpiringList(
    state: ExpiringUiState.Content,
    onThrowAway: (Box) -> Unit,
    reminderCard: @Composable () -> Unit,
    modifier: Modifier
) {
    val overview = state.overview
    LazyColumn(modifier.testTag("expiring.list")) {
        item(key = "reminder-card") { reminderCard() }
        section("expired", R.string.expiring_section_expired, overview.expired, state, onThrowAway)
        section("expiringSoon", R.string.expiring_section_expiringSoon, overview.expiringSoon, state, onThrowAway)
        section("unknown", R.string.expiring_section_unknown, overview.unknown, state, onThrowAway)
    }
}

/** A section header (sticky) and its rows; a section with no box is hidden. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.section(
    id: String,
    title: Int,
    entries: List<ExpiringEntry>,
    state: ExpiringUiState.Content,
    onThrowAway: (Box) -> Unit
) {
    if (entries.isEmpty()) return
    stickyHeader(key = "header:$id") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                .semantics { heading() }
                .testTag("expiring.section.$id")
        )
    }
    items(entries, key = { it.box.id.toString() }) { entry ->
        ExpiringRow(
            entry,
            locationName = state.locationNames[entry.box.locationId].orEmpty(),
            onThrowAway = { onThrowAway(entry.box) }
        )
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
        Text(stringResource(R.string.expiring_loading))
    }
}

@Composable
private fun ErrorMessage(modifier: Modifier, onRetry: () -> Unit) {
    CenteredMessage(
        modifier,
        Icons.Outlined.ErrorOutline,
        stringResource(R.string.expiring_error_title),
        stringResource(R.string.cabinet_error_message)
    ) {
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("expiring.retry")) {
            Text(stringResource(R.string.cabinet_error_retry))
        }
    }
}

/** Nothing to look at; the message names the window ("Nothing expires in the next 60 days"). */
@Composable
private fun EmptyMessage(state: ExpiringUiState.Empty, modifier: Modifier, reminderCard: @Composable () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        reminderCard()
        CenteredMessage(
            Modifier.fillMaxWidth().testTag("expiring.empty"),
            Icons.Outlined.CheckCircle,
            stringResource(R.string.expiring_empty_title),
            stringResource(R.string.expiring_empty_message, state.window.days),
            scrolls = false
        ) { }
    }
}
