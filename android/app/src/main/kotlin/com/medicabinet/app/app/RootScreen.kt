package com.medicabinet.app.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.medicabinet.app.R
import com.medicabinet.app.features.MainScaffold

/** Set once bootstrap succeeded; `null` only before that. */
val LocalAppEnvironment = staticCompositionLocalOf<AppEnvironment?> { null }

@Composable
fun RootScreen(viewModel: BootstrapViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    when (val current = state) {
        BootstrapUiState.Loading -> Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.bootstrap_loading))
        }
        is BootstrapUiState.Failed -> BootstrapErrorScreen(
            current.failure,
            onRetry = viewModel::start,
            modifier = modifier
        )
        is BootstrapUiState.Ready -> CompositionLocalProvider(LocalAppEnvironment provides current.environment) {
            MainScaffold(current.environment, modifier = modifier)
        }
    }
}
