package com.medicabinet.app.features.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medicabinet.app.R
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.persistence.AppPaths
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers

/** The section with its view models, built from the environment (absent when the environment has no transfer). */
@Composable
fun TransferSettings(environment: AppEnvironment) {
    val transfer = environment.transfer ?: return
    val context = LocalContext.current.applicationContext
    val export = viewModel {
        ExportViewModel(
            transfer,
            ArchiveFiles(context, AppPaths(context).exportDirectory),
            environment.clock,
            { ZoneId.systemDefault() },
            Dispatchers.IO
        )
    }
    TransferSection(export)
}

/** Settings → **Backup and transfer** (E1, O3, B4): export the cabinet, import one, the automatic backups. */
@Composable
fun TransferSection(export: ExportViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.transfer_section_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.transfer_export_action)) },
            leadingContent = { Icon(Icons.Outlined.Upload, contentDescription = null) },
            modifier = Modifier.clickable(role = Role.Button, onClick = export::start).testTag("transfer.export")
        )
        Text(
            stringResource(R.string.transfer_section_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
    ExportFlow(export)
}
