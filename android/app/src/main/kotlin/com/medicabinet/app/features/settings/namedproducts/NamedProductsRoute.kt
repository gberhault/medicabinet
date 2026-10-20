package com.medicabinet.app.features.settings.namedproducts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.medicabinet.app.app.AppEnvironment

/** Settings → Products you named on the open cabinet. */
@Composable
fun NamedProductsRoute(environment: AppEnvironment, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: NamedProductsViewModel = viewModel(
        factory = viewModelFactory { initializer { NamedProductsViewModel(environment.named) } }
    )
    NamedProductsScreen(viewModel, onBack, modifier)
}
