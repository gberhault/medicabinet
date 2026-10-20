package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.core.ExpiryStatus

/** The words of a status: never colour alone (an unknown expiry is never "OK"). */
fun expiryStatusText(status: ExpiryStatus): Int = when (status) {
    ExpiryStatus.EXPIRED -> R.string.expiry_status_expired
    ExpiryStatus.EXPIRING_SOON -> R.string.expiry_status_expiringSoon
    ExpiryStatus.OK -> R.string.expiry_status_ok
    ExpiryStatus.UNKNOWN -> R.string.expiry_status_unknown
}

private fun icon(status: ExpiryStatus): ImageVector = when (status) {
    ExpiryStatus.EXPIRED -> Icons.Outlined.EventBusy
    ExpiryStatus.EXPIRING_SOON -> Icons.Outlined.Schedule
    ExpiryStatus.OK -> Icons.Outlined.CheckCircle
    ExpiryStatus.UNKNOWN -> Icons.Outlined.QuestionMark
}

@Composable
private fun tint(status: ExpiryStatus): Color = when (status) {
    ExpiryStatus.EXPIRED -> MaterialTheme.colorScheme.error
    ExpiryStatus.EXPIRING_SOON -> MaterialTheme.colorScheme.tertiary
    ExpiryStatus.OK -> MaterialTheme.colorScheme.primary
    ExpiryStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * An expiry status as an icon and text; the text keeps the default colour (contrast), the icon carries the tint. A box
 * that [expiresToday] is still usable and expiring soon, but says so in words.
 */
@Composable
fun ExpiryStatusLabel(status: ExpiryStatus, modifier: Modifier = Modifier, expiresToday: Boolean = false) {
    val words = if (expiresToday) R.string.expiry_status_expiresToday else expiryStatusText(status)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon(status), contentDescription = null, tint = tint(status))
        Text(stringResource(words), style = MaterialTheme.typography.bodyMedium)
    }
}
