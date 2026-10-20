package com.medicabinet.app.features.scan.camera

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder

// Release builds carry no payload capture: these are the empty counterparts of the debug source set.

internal const val DEBUG_ROUTE = "settings/debug"

@Suppress("UnusedParameter")
@Composable
internal fun DebugSettingsRow(onOpen: () -> Unit) = Unit

internal fun NavGraphBuilder.debugRoutes() = Unit
