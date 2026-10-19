package com.medicabinet.app.features.settings.locations

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.medicabinet.app.app.AppEnvironment

/** Settings → Locations on the open cabinet. */
@Composable
fun LocationsRoute(environment: AppEnvironment, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: LocationsViewModel = viewModel(
        factory = viewModelFactory { initializer { LocationsViewModel(environment.locations) } }
    )
    LocationsScreen(viewModel, onBack, modifier)
}
