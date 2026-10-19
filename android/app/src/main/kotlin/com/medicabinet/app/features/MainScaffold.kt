package com.medicabinet.app.features

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.medicabinet.app.R
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.app.features.common.UndoController
import com.medicabinet.app.features.placeholders.FeaturePlaceholder
import com.medicabinet.app.features.scan.ScanScreen
import com.medicabinet.app.features.scan.ScanServices
import com.medicabinet.app.features.scan.ScanViewModel
import com.medicabinet.app.features.scan.camera.DEBUG_ROUTE
import com.medicabinet.app.features.scan.camera.DebugSettingsRow
import com.medicabinet.app.features.scan.camera.debugRoutes
import com.medicabinet.app.features.settings.AboutScreen
import com.medicabinet.app.features.settings.AboutViewModel
import com.medicabinet.app.features.settings.AppInfo
import com.medicabinet.app.features.settings.PackageAppInfo
import com.medicabinet.app.features.settings.SettingsScreen
import com.medicabinet.app.features.settings.locations.LocationsRoute

private const val SETTINGS_HOME = "settings/home"
private const val SETTINGS_ABOUT = "settings/about"
private const val SETTINGS_LOCATIONS = "settings/locations"

private enum class Destination(val route: String, val labelRes: Int, val icon: ImageVector) {
    Scan("scan", R.string.tab_scan, Icons.Outlined.QrCodeScanner),
    Cabinet("cabinet", R.string.tab_cabinet, Icons.Outlined.MedicalServices),
    Expiring("expiring", R.string.tab_expiring, Icons.Outlined.EventBusy),
    Settings("settings", R.string.tab_settings, Icons.Outlined.Settings)
}

/** Four destinations — Scan, Cabinet, Expiring, Settings — as on iOS. Unbuilt features show an empty state. */
@Composable
fun MainScaffold(
    environment: AppEnvironment,
    modifier: Modifier = Modifier,
    appInfo: AppInfo = PackageAppInfo(LocalContext.current),
    scanServices: ScanServices = ScanServices.live(LocalContext.current, environment.reference)
) {
    val aboutViewModel = remember(appInfo, environment.reference) { AboutViewModel(appInfo, environment.reference) }
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoController = remember(snackbars, scope) { UndoController(snackbars, scope) }
    CompositionLocalProvider(LocalUndoController provides undoController) {
        MainContent(
            navController,
            aboutViewModel,
            environment,
            scanServices,
            snackbars,
            backStack?.destination,
            modifier
        )
    }
}

@Composable
private fun MainContent(
    navController: NavHostController,
    aboutViewModel: AboutViewModel,
    environment: AppEnvironment,
    scanServices: ScanServices,
    snackbars: SnackbarHostState,
    current: NavDestination?,
    modifier: Modifier
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val label = stringResource(destination.labelRes)
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        MainNavHost(navController, aboutViewModel, environment, scanServices, Modifier.padding(padding))
    }
}

@Composable
private fun MainNavHost(
    navController: NavHostController,
    aboutViewModel: AboutViewModel,
    environment: AppEnvironment,
    scanServices: ScanServices,
    modifier: Modifier
) {
    NavHost(navController, startDestination = Destination.Scan.route, modifier = modifier) {
        composable(Destination.Scan.route) {
            val model = viewModel { ScanViewModel(environment, scanServices) }
            ScanScreen(model, scanServices)
        }
        composable(Destination.Cabinet.route) {
            FeaturePlaceholder(
                R.string.placeholder_cabinet_title,
                R.string.placeholder_cabinet_message,
                Destination.Cabinet.icon
            )
        }
        composable(Destination.Expiring.route) {
            FeaturePlaceholder(
                R.string.placeholder_expiring_title,
                R.string.placeholder_expiring_message,
                Destination.Expiring.icon
            )
        }
        navigation(route = Destination.Settings.route, startDestination = SETTINGS_HOME) {
            composable(SETTINGS_HOME) {
                SettingsScreen(
                    onOpenAbout = { navController.navigate(SETTINGS_ABOUT) },
                    onOpenLocations = { navController.navigate(SETTINGS_LOCATIONS) },
                    extraRows = { DebugSettingsRow { navController.navigate(DEBUG_ROUTE) } }
                )
            }
            composable(SETTINGS_ABOUT) { AboutScreen(aboutViewModel, onBack = { navController.popBackStack() }) }
            composable(SETTINGS_LOCATIONS) { LocationsRoute(environment, onBack = { navController.popBackStack() }) }
            debugRoutes()
        }
    }
}
