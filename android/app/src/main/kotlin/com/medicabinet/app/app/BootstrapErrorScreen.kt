package com.medicabinet.app.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R

/** Blocking screen shown when the cabinet cannot be opened. Never offers to delete anything. */
@Composable
fun BootstrapErrorScreen(failure: BootstrapFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val (title, message) = when (failure) {
        BootstrapFailure.BACKUP -> R.string.bootstrap_error_backup_title to R.string.bootstrap_error_backup_message
        BootstrapFailure.MIGRATION ->
            R.string.bootstrap_error_migration_title to
                R.string.bootstrap_error_migration_message
        BootstrapFailure.STORAGE -> R.string.bootstrap_error_storage_title to R.string.bootstrap_error_storage_message
    }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text(stringResource(R.string.bootstrap_retry)) }
    }
}
