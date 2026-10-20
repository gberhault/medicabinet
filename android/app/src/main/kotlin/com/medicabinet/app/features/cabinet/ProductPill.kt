package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.cabinet.ProductRow

private const val NAME_LINES = 2
private val PILL_HEIGHT = 32.dp
private val STATUS_ICON = 18.dp

/**
 * One product of the Cabinet as a pill: its displayed name and its box count. No date and no "OK": only the alert
 * statuses (expired, expiring soon, unknown expiry) are marked, with an icon and a tint — never colour alone. The pill
 * draws 32 dp high and takes taps over 48 dp. TalkBack reads the full name, the count and, for an alert, the status.
 */
@Composable
fun ProductPill(row: ProductRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val alert = PillColors.alert(row.status, dark)
    val container = alert?.first ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val content = alert?.second ?: MaterialTheme.colorScheme.onSurface
    val border = if (row.status == ExpiryStatus.UNKNOWN) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
    val boxes = pluralStringResource(R.plurals.cabinet_row_boxes, row.boxes.size, row.boxes.size)
    val spoken = if (row.status == ExpiryStatus.OK) {
        stringResource(R.string.cabinet_pill_label, row.name, boxes)
    } else {
        stringResource(R.string.cabinet_pill_labelStatus, row.name, boxes, stringResource(expiryStatusText(row.status)))
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = border,
        // The tap target is 48 dp even though the pill draws 32 dp.
        modifier = modifier.minimumInteractiveComponentSize().semantics { contentDescription = spoken }
    ) {
        Row(
            Modifier.heightIn(min = PILL_HEIGHT).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (row.status != ExpiryStatus.OK) {
                Icon(expiryStatusIcon(row.status), contentDescription = null, modifier = Modifier.size(STATUS_ICON))
            }
            Text(
                row.displayName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = NAME_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { }
            )
            Text(
                "×${row.boxes.size}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
    }
}
