package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicabinet.app.R
import com.medicabinet.app.features.CategoryLabels
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.app.features.scan.ScanText
import com.medicabinet.core.Box as CabinetBox
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * One box: what it is, where it is, whether it is still good — never the serial. The user can edit it, mark it opened
 * and take it out (used up, thrown away, added by mistake), each with **Undo** and no confirmation dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxDetailScreen(model: BoxDetailViewModel, zone: ZoneId, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    // The single way out: the box left the cabinet (taken out here, or by anything else).
    LaunchedEffect(state is BoxDetailState.Gone) { if (state is BoxDetailState.Gone) onBack() }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.box_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("box.back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.about_back))
                    }
                },
                actions = {
                    if (state is BoxDetailState.Loaded) {
                        TextButton(
                            onClick = { editing = true },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("box.edit")
                        ) { Text(stringResource(R.string.box_edit_action)) }
                    }
                }
            )
        }
    ) { padding ->
        val content = Modifier.padding(padding).fillMaxSize()
        when (val current = state) {
            BoxDetailState.Loading, BoxDetailState.Gone -> Box(content, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            BoxDetailState.Failed -> FailedMessage(content, model::retry)
            is BoxDetailState.Loaded -> {
                BoxDetailContent(model, current, zone, content)
                if (editing) BoxEditSheet(model, current, onDismiss = { editing = false })
            }
        }
    }
}

@Composable
private fun BoxDetailContent(
    model: BoxDetailViewModel,
    state: BoxDetailState.Loaded,
    zone: ZoneId,
    modifier: Modifier
) {
    val box = state.box
    val failed by model.writeFailed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (failed) WriteFailedBanner(model::acknowledgeWriteFailure)
        Text(
            box.product.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }.testTag("box.name")
        )
        Field(R.string.box_code, BoxCode.display(box.product.gtin) ?: stringResource(R.string.box_noCode), "box.code")
        CategoryRow(box)
        Field(R.string.box_location, state.locationName, "box.location")
        ExpiryRow(box, state)
        OpenedRow(box.isOpened) { scope.launch { model.setOpened(it) } }
        box.lot?.let { Field(R.string.box_lot, it, "box.lot") }
        Field(R.string.box_added, addedDate(box, zone), "box.added")
        TakeOutMenu(model)
    }
}

@Composable
private fun Field(labelRes: Int, value: String, tag: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag)) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The category, whether the user chose it, and the reminder that categories are for tidying (B4, B8). */
@Composable
private fun CategoryRow(box: CabinetBox) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("box.category")) {
        Text(
            stringResource(R.string.box_category),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(stringResource(CategoryLabels.resource(box.category)), style = MaterialTheme.typography.bodyLarge)
        if (box.categoryOverridden) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.box_category_manual), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            stringResource(R.string.box_category_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The expiry date (when known) with its status as text and icon; "expiry unknown" is never shown as OK. */
@Composable
private fun ExpiryRow(box: CabinetBox, state: BoxDetailState.Loaded) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("box.expiry")) {
        Text(
            stringResource(R.string.box_expiry),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // An unknown expiry is said once, by the status label.
        box.expiry?.let { Text(ScanText.expiry(context, it), style = MaterialTheme.typography.bodyLarge) }
        ExpiryStatusLabel(state.status)
    }
}

@Composable
private fun OpenedRow(opened: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(opened, role = Role.Switch, onValueChange = onChange)
            .testTag("box.opened"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.box_opened), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = opened, onCheckedChange = null)
    }
}

/** Used up / Thrown away / Added by mistake. Each leaves the screen and offers **Undo**; none asks to confirm. */
@Composable
private fun TakeOutMenu(model: BoxDetailViewModel) {
    val context = LocalContext.current
    val undoController = LocalUndoController.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val undoLabel = stringResource(R.string.undo_action)
    val restoreFailed = stringResource(R.string.undo_restoreFailed)
    val saveFailed = stringResource(R.string.box_error)
    fun takeOut(action: TakeOut) {
        open = false
        scope.launch {
            val offer = model.takeOut(action) ?: return@launch
            val message = context.getString(undoMessage(action), offer.productName)
            undoController?.show(message, undoLabel) {
                when (offer.undo()) {
                    UndoOutcome.RESTORED -> Unit
                    UndoOutcome.SERIAL_TAKEN -> undoController.notify(restoreFailed)
                    UndoOutcome.FAILED -> undoController.notify(saveFailed)
                }
            }
        }
    }
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("box.takeOut")
        ) { Text(stringResource(R.string.box_remove_menu)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.box_remove_usedUp)) },
                onClick = { takeOut(TakeOut.USED_UP) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("box.takeOut.usedUp")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.box_remove_thrownAway)) },
                onClick = { takeOut(TakeOut.THROWN_AWAY) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("box.takeOut.thrownAway")
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.box_remove_mistake))
                        Text(
                            stringResource(R.string.box_remove_mistakeExplanation),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                onClick = { takeOut(TakeOut.MISTAKE) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("box.takeOut.mistake")
            )
        }
    }
}

private fun undoMessage(action: TakeOut): Int = when (action) {
    TakeOut.USED_UP -> R.string.undo_usedUp
    TakeOut.THROWN_AWAY -> R.string.undo_thrownAway
    TakeOut.MISTAKE -> R.string.undo_deleted
}

@Composable
private fun addedDate(box: CabinetBox, zone: ZoneId): String {
    val locale: Locale = LocalContext.current.resources.configuration.locales[0]
    return box.createdAt.atZone(zone).toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}

@Composable
private fun WriteFailedBanner(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("box.error"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.box_error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.locations_changeFailed_ok))
        }
    }
}

@Composable
private fun FailedMessage(modifier: Modifier, onRetry: () -> Unit) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.cabinet_error_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.cabinet_error_message), textAlign = TextAlign.Center)
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("box.retry")) {
            Text(stringResource(R.string.cabinet_error_retry))
        }
    }
}
