package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R
import com.medicabinet.app.features.CategoryIcons
import com.medicabinet.app.features.CategoryLabels
import com.medicabinet.core.cabinet.CategorySection
import com.medicabinet.core.cabinet.ProductRow

private val CARD_RADIUS = 16.dp
private val PILL_GAP = 6.dp

/** A category of the Cabinet: its icon, label and box total, then its products as pills that wrap across the width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryCard(section: CategorySection, onOpenProduct: (ProductRow) -> Unit, modifier: Modifier = Modifier) {
    val total = section.rows.sumOf { it.boxes.size }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CARD_RADIUS),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics(mergeDescendants = true) { heading() }
                    .testTag("cabinet.category"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    CategoryIcons.icon(section.category),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(CategoryLabels.resource(section.category)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    pluralStringResource(R.plurals.cabinet_row_boxes, total, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(PILL_GAP)) {
                section.rows.forEach { row ->
                    ProductPill(row, onClick = { onOpenProduct(row) }, modifier = Modifier.testTag("cabinet.pill"))
                }
            }
        }
    }
}
