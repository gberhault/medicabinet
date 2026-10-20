package com.medicabinet.app.features.expiring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.app.features.cabinet.ExpiryStatusLabel
import com.medicabinet.app.features.cabinet.expiryStatusText
import com.medicabinet.core.expiry.ExpiringEntry

/**
 * One box needing a look: name, location, expiry (month and year for a month-precise date) and the status as text and
 * icon. **Thrown away** is a swipe and an accessibility action; the row opens nothing (box detail comes with #20).
 *
 * The swipe always springs back: the row leaves the list when the cabinet changes, so a failed write leaves it
 * exactly as it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpiringRow(entry: ExpiringEntry, locationName: String, onThrowAway: () -> Unit, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(onThrowAway)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) latest()
            false
        }
    )
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = { ThrownAwayBackground() },
        modifier = modifier.testTag("expiring.row")
    ) {
        RowContent(entry, locationName, onThrowAway)
    }
}

@Composable
private fun ThrownAwayBackground() {
    // Visual only: the accessibility action carries the words for TalkBack.
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.CenterEnd
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.expiring_thrownAway),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelLarge
            )
            Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun RowContent(entry: ExpiringEntry, locationName: String, onThrowAway: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val expiry = entry.box.expiry
    val status = entry.assessment.status
    val expiryLine = expiry?.let { stringResource(R.string.expiring_row_expiry, ExpiryText.date(it, locale)) }
    val statusWords = stringResource(
        if (entry.assessment.expiresToday) R.string.expiry_status_expiresToday else expiryStatusText(status)
    )
    val thrownAway = stringResource(R.string.expiring_thrownAway)
    // The spoken row: name, status, expiry line, location. An unknown expiry is said once, by the status.
    val spoken = expiryLine?.let {
        stringResource(R.string.expiring_row_accessibility, entry.box.product.name, statusWords, it, locationName)
    }
    // A custom accessibility action reports whether it handled the request.
    val action: () -> Boolean = {
        onThrowAway()
        true
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) {
                if (spoken != null) contentDescription = spoken
                customActions = listOf(CustomAccessibilityAction(thrownAway, action))
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(entry.box.product.name, style = MaterialTheme.typography.titleMedium)
        Text(locationName, style = MaterialTheme.typography.bodyMedium)
        if (expiryLine != null) Text(expiryLine, style = MaterialTheme.typography.bodyMedium)
        ExpiryStatusLabel(status, expiresToday = entry.assessment.expiresToday)
    }
}
