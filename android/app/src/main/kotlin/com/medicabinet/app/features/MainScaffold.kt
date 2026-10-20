package com.medicabinet.app.features

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.medicabinet.app.app.AppTab
import com.medicabinet.app.app.TabRouter
import com.medicabinet.app.features.cabinet.cabinetGraph
import com.medicabinet.app.features.common.LocalUndoController
import com.medicabinet.app.features.common.UndoController
import com.medicabinet.app.features.expiring.ExpiringScreen
import com.medicabinet.app.features.expiring.ExpiringViewModel
import com.medicabinet.app.features.expiring.ReminderActions
import com.medicabinet.app.features.expiring.ReminderCard
import com.medicabinet.app.features.expiring.rememberReminderActions
import com.medicabinet.app.features.scan.ScanScreen
import com.medicabinet.app.features.scan.ScanServices
import com.medicabinet.app.features.scan.ScanViewModel
import com.medicabinet.app.features.scan.camera.DEBUG_ROUTE
import com.medicabinet.app.features.scan.camera.DebugSettingsRow
import com.medicabinet.app.features.scan.camera.debugRoutes
import com.medicabinet.app.features.settings.AboutScreen
import com.medicabinet.app.features.settings.AboutViewModel
import com.medicabinet.app.features.settings.AppInfo
import com.medicabinet.app.features.settings.ExpirySettingsSection
import com.medicabinet.app.features.settings.PackageAppInfo
import com.medicabinet.app.features.settings.SettingsScreen
import com.medicabinet.app.features.settings.locations.LocationsRoute
import com.medicabinet.app.features.transfer.ImportCoordinator
import com.medicabinet.app.features.transfer.LocalImportCoordinator
import com.medicabinet.app.features.transfer.OpenIncomingImport
import com.medicabinet.app.features.transfer.PendingImportBanner
import com.medicabinet.app.features.transfer.TransferRoutes
import com.medicabinet.app.features.transfer.TransferSettings
import com.medicabinet.app.features.transfer.transferRoutes
import com.medicabinet.app.reminders.ReminderModel
import com.medicabinet.app.reminders.ReminderServices
import com.medicabinet.app.reminders.reminderServices
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val SETTINGS_HOME = "settings/home"
private const val SETTINGS_ABOUT = "settings/about"
private const val SETTINGS_LOCATIONS = "settings/locations"

/** The models shared by several tabs: the Expiring list and its badge, and the monthly-reminder setting. */
private class MainModels(
    val expiring: ExpiringViewModel,
    val reminder: ReminderModel,
    val reminderActions: ReminderActions
)

private enum class Destination(val tab: AppTab, val labelRes: Int, val icon: ImageVector) {
    Scan(AppTab.SCAN, R.string.tab_scan, Icons.Outlined.QrCodeScanner),
    Cabinet(AppTab.CABINET, R.string.tab_cabinet, Icons.Outlined.MedicalServices),
    Expiring(AppTab.EXPIRING, R.string.tab_expiring, Icons.Outlined.EventBusy),
    Settings(AppTab.SETTINGS, R.string.tab_settings, Icons.Outlined.Settings);

    val route: String get() = tab.route
}

/**
 * Four destinations — Scan, Cabinet, Expiring, Settings — as on iOS; the app opens on the Cabinet
 * ([AppTab.LAUNCH]). The Expiring model lives here, above the tabs, so the badge on the tab is live whichever tab is
 * shown and always agrees with the list. [tabRouter] lets the notification tap switch tab from outside.
 */
@Composable
fun MainScaffold(
    environment: AppEnvironment,
    modifier: Modifier = Modifier,
    appInfo: AppInfo = PackageAppInfo(LocalContext.current),
    scanServices: ScanServices = ScanServices.live(LocalContext.current, environment.reference),
    reminderServices: ReminderServices = LocalContext.current.reminderServices(),
    tabRouter: TabRouter = remember { TabRouter() },
    importCoordinator: ImportCoordinator? = null
) {
    val aboutViewModel = remember(appInfo, environment.reference) { AboutViewModel(appInfo, environment.reference) }
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoController = remember(snackbars, scope) { UndoController(snackbars, scope) }
    val reminderModel = viewModel {
        ReminderModel(
            environment.expiryPreferences,
            reminderServices.access,
            reminderServices.scheduling,
            environment.clock
        ) { ZoneId.systemDefault() }
    }
    // The system may have changed what is allowed while the app was away (notification settings).
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        reminderModel.refresh()
        // Re-arm the digest alarm: it may have been lost (OEM task killers) or the zone or clock may have changed.
        scope.launch(Dispatchers.IO) { reminderServices.scheduling.arm() }
    }
    val models = MainModels(
        expiring = viewModel { ExpiringViewModel(environment) },
        reminder = reminderModel,
        reminderActions = rememberReminderActions(reminderModel)
    )
    importCoordinator?.let { coordinator ->
        OpenIncomingImport(coordinator, environment) {
            navController.openTab(Destination.Settings)
            navController.navigate(TransferRoutes.IMPORT)
        }
    }
    val requested by tabRouter.requested.collectAsState()
    // The NavHost is composed inside the Scaffold, one pass after this effect first runs: a request already waiting
    // at a cold start (the notification tap) must wait for the graph, or navigating would throw.
    val graphReady = backStack != null
    LaunchedEffect(requested, graphReady) {
        requested?.takeIf { graphReady }?.let { tab ->
            navController.openTab(Destination.entries.first { it.tab == tab })
            tabRouter.consume(tab)
        }
    }
    CompositionLocalProvider(
        LocalUndoController provides undoController,
        LocalImportCoordinator provides importCoordinator
    ) {
        MainContent(
            navController,
            aboutViewModel,
            environment,
            scanServices,
            models,
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
    models: MainModels,
    snackbars: SnackbarHostState,
    current: NavDestination?,
    modifier: Modifier
) {
    val attention by models.expiring.attentionCount.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val label = stringResource(destination.labelRes)
                    val badge = attention.takeIf { destination == Destination.Expiring && it > 0 }
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = { navController.openTab(destination) },
                        icon = { TabIcon(destination, badge) },
                        label = { Text(label) },
                        // The item clears its icon's semantics, so the item itself says the count (TalkBack).
                        modifier = badge?.let { Modifier.badgeSemantics(label, it) } ?: Modifier
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            LocalImportCoordinator.current?.let { PendingImportBanner(it) }
            MainNavHost(navController, aboutViewModel, environment, scanServices, models, Modifier.weight(1f))
        }
    }
}

/** The tab icon; the Expiring tab carries the number of boxes needing attention ([badge], absent at zero). */
@Composable
private fun TabIcon(destination: Destination, badge: Int?) {
    if (badge == null) {
        Icon(destination.icon, contentDescription = null)
        return
    }
    BadgedBox(badge = { Badge { Text(badge.toString()) } }) {
        Icon(destination.icon, contentDescription = null)
    }
}

/** "Expiring, 3 boxes need attention": the label, then the count in words. */
@Composable
private fun Modifier.badgeSemantics(label: String, count: Int): Modifier {
    val description = pluralStringResource(R.plurals.expiring_badge_accessibility, count, count)
    return semantics { contentDescription = "$label, $description" }
}

/** Switches to a bottom-bar tab, keeping each tab's own back stack. */
private fun NavHostController.openTab(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun MainNavHost(
    navController: NavHostController,
    aboutViewModel: AboutViewModel,
    environment: AppEnvironment,
    scanServices: ScanServices,
    models: MainModels,
    modifier: Modifier
) {
    NavHost(navController, startDestination = AppTab.LAUNCH.route, modifier = modifier) {
        composable(Destination.Scan.route) {
            val model = viewModel { ScanViewModel(environment, scanServices) }
            ScanScreen(model, scanServices)
        }
        cabinetGraph(navController, environment) { navController.openTab(Destination.Scan) }
        composable(Destination.Expiring.route) {
            ExpiringScreen(models.expiring, reminderCard = { ReminderCard(models.reminder, models.reminderActions) })
        }
        navigation(route = Destination.Settings.route, startDestination = SETTINGS_HOME) {
            composable(SETTINGS_HOME) {
                SettingsScreen(
                    onOpenAbout = { navController.navigate(SETTINGS_ABOUT) },
                    onOpenLocations = { navController.navigate(SETTINGS_LOCATIONS) },
                    expirySection = {
                        ExpirySettingsSection(environment.expiryPreferences, models.reminder, models.reminderActions)
                    },
                    transferSection = {
                        TransferSettings(environment, onOpenBackups = {
                            navController.navigate(TransferRoutes.BACKUPS)
                        })
                    },
                    extraRows = { DebugSettingsRow { navController.navigate(DEBUG_ROUTE) } }
                )
            }
            composable(SETTINGS_ABOUT) { AboutScreen(aboutViewModel, onBack = { navController.popBackStack() }) }
            composable(SETTINGS_LOCATIONS) { LocationsRoute(environment, onBack = { navController.popBackStack() }) }
            transferRoutes(navController, environment)
            debugRoutes()
        }
    }
}
