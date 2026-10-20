package com.medicabinet.app.features.settings.locations

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicabinet.app.R
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.core.Location
import java.util.UUID

/** What the name dialog is for: a new location, or renaming one. */
private sealed interface Naming {
    data object Add : Naming

    data class Rename(val location: Location) : Naming
}

/** Settings → Locations: where the household keeps its medicines (bathroom, kitchen, car, travel kit…). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(viewModel: LocationsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf<Naming?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.locations_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.about_back))
                    }
                },
                actions = {
                    IconButton(onClick = { naming = Naming.Add }, modifier = Modifier.testTag("locations.add")) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.locations_add))
                    }
                }
            )
        }
    ) { padding ->
        val content = Modifier.padding(padding).fillMaxSize()
        when (val current = state) {
            LocationsUiState.Loading -> StatusMessage(content, loading = true)
            LocationsUiState.Failed -> StatusMessage(content, loading = false, onRetry = viewModel::observe)
            is LocationsUiState.Loaded -> LocationList(
                current.locations,
                viewModel,
                onRename = { naming = Naming.Rename(it) },
                modifier = content
            )
        }
    }
    LocationsOverlays(
        viewModel,
        naming = naming,
        onNamingDone = { naming = null }
    )
}

/** The dialogs and the undo offer, driven by the view model. */
@Composable
private fun LocationsOverlays(viewModel: LocationsViewModel, naming: Naming?, onNamingDone: () -> Unit) {
    val deletion by viewModel.deletions.deletion.collectAsStateWithLifecycle()
    val changeFailed by viewModel.writes.failed.collectAsStateWithLifecycle()
    val offer by viewModel.deletions.undoOffer.collectAsStateWithLifecycle()
    val undoController = LocalUndoController.current
    val message = stringResource(R.string.locations_deleted, offer?.locationName.orEmpty())
    val undoLabel = stringResource(R.string.undo_action)
    LaunchedEffect(offer) {
        offer?.let {
            undoController?.show(message, undoLabel, it.undo)
            viewModel.deletions.undoOfferShown()
        }
    }
    when (naming) {
        null -> Unit
        Naming.Add -> LocationNameDialog(viewModel, renaming = null, onDismiss = onNamingDone)
        is Naming.Rename -> LocationNameDialog(viewModel, renaming = naming.location, onDismiss = onNamingDone)
    }
    deletion?.let {
        DeleteLocationDialog(
            it,
            onConfirm = { destination -> viewModel.deletions.confirm(it, destination) },
            onDismiss = viewModel.deletions::cancel
        )
    }
    if (changeFailed) ChangeFailedDialog(onDismiss = viewModel.writes::acknowledge)
}

@Composable
private fun StatusMessage(modifier: Modifier, loading: Boolean, onRetry: () -> Unit = {}) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator()
            Text(stringResource(R.string.locations_loading))
        } else {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.locations_error_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.locations_error_message), textAlign = TextAlign.Center)
            Button(onClick = onRetry) { Text(stringResource(R.string.locations_error_retry)) }
        }
    }
}

@Composable
private fun LocationList(
    locations: List<Location>,
    viewModel: LocationsViewModel,
    onRename: (Location) -> Unit,
    modifier: Modifier = Modifier
) {
    val heights = remember { mutableStateMapOf<UUID, Int>() }
    var dragging by remember { mutableStateOf<UUID?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LazyColumn(modifier.testTag("locations.list")) {
        itemsIndexed(locations, key = { _, location -> location.id }) { index, location ->
            val dragged = location.id == dragging
            LocationRow(
                location,
                viewModel,
                onRename = onRename,
                onDragStart = {
                    dragging = location.id
                    dragOffset = 0f
                },
                onDrag = { dragOffset += it },
                onDragEnd = {
                    val target = reorderTarget(index, dragOffset, locations.map { heights[it.id] ?: 1 })
                    dragging = null
                    dragOffset = 0f
                    viewModel.move(index, target)
                },
                modifier = Modifier
                    .onSizeChanged { heights[location.id] = it.height }
                    .zIndex(if (dragged) 1f else 0f)
                    .graphicsLayer { translationY = if (dragged) dragOffset else 0f }
            )
        }
        item {
            Text(
                stringResource(R.string.locations_defaultNotDeletable),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun LocationRow(
    location: Location,
    viewModel: LocationsViewModel,
    onRename: (Location) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val moveUp = stringResource(R.string.locations_move_up)
    val moveDown = stringResource(R.string.locations_move_down)
    val reorder = stringResource(R.string.locations_reorder)
    ListItem(
        headlineContent = { Text(location.name) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (location.isDefault) DefaultMarker()
                RowMenu(location, viewModel, onRename)
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = reorder,
                    modifier = Modifier
                        .size(48.dp)
                        .padding(12.dp)
                        .pointerInput(location.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onDragStart() },
                                onDrag = { change, amount ->
                                    change.consume()
                                    onDrag(amount.y)
                                },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragEnd
                            )
                        }
                        .testTag("locations.handle")
                )
            }
        },
        modifier = modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(moveUp) {
                    viewModel.shift(location, -1)
                    true
                },
                CustomAccessibilityAction(moveDown) {
                    viewModel.shift(location, 1)
                    true
                }
            )
        }
    )
}

/** The default is marked with an icon and text, never colour alone. */
@Composable
private fun DefaultMarker() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("locations.default")) {
        Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.locations_default),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun RowMenu(location: Location, viewModel: LocationsViewModel, onRename: (Location) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("locations.menu")) {
        Icon(Icons.Outlined.MoreVert, stringResource(R.string.locations_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.locations_rename)) },
            onClick = {
                open = false
                onRename(location)
            }
        )
        if (!location.isDefault) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.locations_makeDefault)) },
                onClick = {
                    open = false
                    viewModel.makeDefault(location)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.locations_delete)) },
                onClick = {
                    open = false
                    viewModel.deletions.request(location)
                }
            )
        }
    }
}
