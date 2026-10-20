package com.medicabinet.app.features.cabinet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicabinet.app.R
import com.medicabinet.app.features.scan.ScanText
import com.medicabinet.core.Box as CabinetBox
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.Location
import com.medicabinet.core.cabinet.ProductRow
import com.medicabinet.core.cabinet.ProductRowId
import com.medicabinet.core.expiry.CalendarDay

/**
 * The boxes of one product (P1), earliest expiry first, under the current location and status filters. It reads the
 * Cabinet list's state, so it follows the same changes; when its last box leaves the cabinet it goes back (P3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductBoxesScreen(
    model: CabinetViewModel,
    rowId: ProductRowId,
    expiryStatus: (ExpiryDate?, CalendarDay) -> ExpiryStatus,
    onOpenBox: (CabinetBox) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by model.state.collectAsStateWithLifecycle()
    val loaded = state as? CabinetUiState.Loaded
    val row = loaded?.snapshot?.sections?.flatMap { it.rows }?.firstOrNull { it.id == rowId }
    LaunchedEffect(loaded != null && row == null) { if (loaded != null && row == null) onBack() }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(row?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("product.back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.about_back))
                    }
                }
            )
        }
    ) { padding ->
        val content = Modifier.padding(padding).fillMaxSize()
        if (loaded == null || row == null) {
            Box(content, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            BoxList(row, loaded.locations, { expiryStatus(it.expiry, loaded.today) }, onOpenBox, content)
        }
    }
}

@Composable
private fun BoxList(
    row: ProductRow,
    locations: List<Location>,
    statusOf: (CabinetBox) -> ExpiryStatus,
    onOpenBox: (CabinetBox) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier.testTag("product.list"), contentPadding = PaddingValues(16.dp)) {
        items(row.boxes, key = { it.id }) { box ->
            BoxItem(box, locations.firstOrNull { it.id == box.locationId }?.name.orEmpty(), statusOf(box)) {
                onOpenBox(box)
            }
            HorizontalDivider()
        }
    }
}

/** One box: its expiry (or "expiry unknown") with status text and icon, its location, and "opened". */
@Composable
private fun BoxItem(box: CabinetBox, locationName: String, status: ExpiryStatus, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag("product.box"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // An unknown expiry is said once, by the status label.
        box.expiry?.let { Text(ScanText.expiry(context, it), style = MaterialTheme.typography.titleMedium) }
        ExpiryStatusLabel(status)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(locationName, style = MaterialTheme.typography.bodyMedium)
        }
        if (box.isOpened) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.box_opened), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
