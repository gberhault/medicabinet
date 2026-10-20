package com.medicabinet.app.features.scan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R

private const val BAR_ALPHA = 0.9f
private const val PILL_PERCENT = 50
private val CARD_CORNER = 24.dp

/** Add / Remove (R1). TalkBack is told about the change: the mode alters what a scan does. */
@Composable
internal fun ModePicker(mode: ScanMode, onSelect: (ScanMode) -> Unit) {
    val view = LocalView.current
    val announcements = mapOf(
        ScanMode.ADD to stringResource(R.string.scan_mode_announcement_add),
        ScanMode.REMOVE to stringResource(R.string.scan_mode_announcement_remove)
    )
    var announced by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        if (mode != announced) {
            announced = mode
            @Suppress("DEPRECATION")
            view.announceForAccessibility(announcements.getValue(mode))
        }
    }
    val label = stringResource(R.string.scan_mode_picker)
    Surface(
        shape = RoundedCornerShape(PILL_PERCENT),
        color = MaterialTheme.colorScheme.surface.copy(alpha = BAR_ALPHA)
    ) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).semantics { contentDescription = label }
        ) {
            val modes = listOf(
                ScanMode.ADD to R.string.scan_mode_add,
                ScanMode.REMOVE to R.string.scan_mode_remove
            )
            modes.forEachIndexed { index, (entry, title) ->
                SegmentedButton(
                    selected = mode == entry,
                    onClick = { onSelect(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("scan.mode.${entry.name.lowercase()}")
                ) { Text(stringResource(title)) }
            }
        }
    }
}

/** Remove mode says so in text, icon and colour (R2) and carries the session reason (R3). */
@Composable
internal fun RemoveBar(model: ScanViewModel, state: ScanUiState) {
    Surface(
        shape = RoundedCornerShape(CARD_CORNER),
        color = MaterialTheme.colorScheme.surface.copy(alpha = BAR_ALPHA),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error)
    ) {
        // Two rows so that nothing is pushed out of the bar at 200 % font scale.
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.RemoveCircleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    stringResource(R.string.scan_remove_title),
                    Modifier.padding(horizontal = 8.dp).semantics { heading() }.testTag("scan.remove.title"),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemovalReasonChip(state.removalReason, onSelect = model::setRemovalReason)
                Box(Modifier.weight(1f))
                SoundButton(model, state)
            }
        }
    }
}

@Composable
internal fun SoundButton(model: ScanViewModel, state: ScanUiState) {
    val sound = stringResource(if (state.soundEnabled) R.string.scan_sound_on else R.string.scan_sound_off)
    IconButton(
        onClick = { model.setSound(!state.soundEnabled) },
        modifier = Modifier.semantics { contentDescription = sound }.testTag("scan.sound")
    ) {
        Icon(if (state.soundEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, contentDescription = null)
    }
}

@Composable
internal fun TopBar(model: ScanViewModel, state: ScanUiState, onManual: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(PILL_PERCENT),
        color = MaterialTheme.colorScheme.surface.copy(alpha = BAR_ALPHA)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            var open by remember { mutableStateOf(false) }
            val change = stringResource(R.string.scan_location_change)
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { open = true },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = change
                    }.testTag("scan.location")
                ) {
                    Icon(Icons.Outlined.Place, contentDescription = null)
                    Text(
                        stringResource(R.string.scan_location_label, state.sessionLocation?.name.orEmpty()),
                        maxLines = 1
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    state.locations.forEach { location ->
                        DropdownMenuItem(text = { Text(location.name) }, onClick = {
                            model.selectLocation(location.id)
                            open =
                                false
                        })
                    }
                }
            }
            SoundButton(model, state)
            val manual = stringResource(R.string.scan_manual_open)
            IconButton(
                onClick = onManual,
                modifier = Modifier.semantics {
                    contentDescription = manual
                }.testTag("scan.manual.open")
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null)
            }
        }
    }
}
