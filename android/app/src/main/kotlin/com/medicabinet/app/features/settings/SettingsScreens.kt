package com.medicabinet.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.medicabinet.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLocations: () -> Unit = {},
    extraRows: @Composable () -> Unit = {}
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_locations)) },
                leadingContent = { Icon(Icons.Outlined.Place, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenLocations).testTag("settings.locations")
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_about)) },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenAbout)
            )
            extraRows()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(viewModel: AboutViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.about_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, viewModel.version, viewModel.build))
            Text(stringResource(R.string.about_disclaimer), modifier = Modifier.padding(top = 24.dp))
            ReferenceDataSection(viewModel, Modifier.padding(top = 24.dp))
        }
    }
}

/** The source of the bundled data (the licence asks for it), its date, and that categories are MediCabinet's own. */
@Composable
private fun ReferenceDataSection(viewModel: AboutViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.referenceState.collectAsState()
    val uriHandler = LocalUriHandler.current
    val openLabel = stringResource(R.string.about_reference_open)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.about_reference_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_reference_source))
        Text(
            AboutViewModel.REFERENCE_ADDRESS,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clickable(onClickLabel = openLabel, role = Role.Button) {
                    runCatching { uriHandler.openUri("https://${AboutViewModel.REFERENCE_ADDRESS}") }
                }
                .wrapContentHeight(Alignment.CenterVertically)
        )
        when (val current = state) {
            ReferenceUiState.Loading -> Text(stringResource(R.string.about_reference_loading))
            is ReferenceUiState.Available -> {
                val date = AboutViewModel.formatDate(current.dataDate, LocalConfiguration.current.locales[0])
                Text(stringResource(R.string.about_reference_date, date))
            }
            ReferenceUiState.Unavailable -> Text(stringResource(R.string.about_reference_unavailable))
        }
        Text(stringResource(R.string.about_reference_notEndorsed))
    }
}
