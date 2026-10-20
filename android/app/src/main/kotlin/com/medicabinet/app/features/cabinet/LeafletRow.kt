package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.app.features.common.LocalUndoController

/**
 * The official BDPM page of the medicine (it carries the leaflet), in the system browser. Shown only once the code has
 * been looked up and the database knows it, below the information so nothing above it moves.
 */
@Composable
internal fun LeafletRow(address: String) {
    val uriHandler = LocalUriHandler.current
    val undo = LocalUndoController.current
    val hint = stringResource(R.string.box_leaflet_hint)
    val failedMessage = stringResource(R.string.box_leaflet_openFailed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = hint, role = Role.Button) {
                    runCatching { uriHandler.openUri(address) }.onFailure { undo?.notify(failedMessage) }
                }
                .testTag("box.leaflet"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.box_leaflet_open),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            stringResource(R.string.box_leaflet_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
