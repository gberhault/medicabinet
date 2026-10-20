package com.medicabinet.app.features.scan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.core.RemovalReason

fun removalReasonLabel(reason: RemovalReason): Int = when (reason) {
    RemovalReason.USED_UP -> R.string.scan_removal_usedUp
    RemovalReason.THROWN_AWAY -> R.string.scan_removal_thrownAway
}

/** The session's removal reason, changeable in one tap (R3): the choice is remembered for next time. */
@Composable
fun RemovalReasonChip(reason: RemovalReason, onSelect: (RemovalReason) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val title = stringResource(R.string.scan_removal_reason)
    val current = stringResource(removalReasonLabel(reason))
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = title
                stateDescription = current
            }.testTag("scan.removal.reason")
        ) {
            Icon(Icons.Outlined.Autorenew, contentDescription = null)
            Text(current, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RemovalReason.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(removalReasonLabel(entry))) },
                    onClick = {
                        onSelect(entry)
                        open = false
                    },
                    modifier = Modifier.testTag("scan.removal.reason.${entry.rawValue}")
                )
            }
        }
    }
}
