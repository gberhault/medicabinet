package com.medicabinet.app.features.cabinet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.medicabinet.app.app.AppEnvironment
import java.util.UUID

/**
 * The Cabinet tab's graph: `cabinet/home` → `cabinet/product/{category}/{key}` → `cabinet/box/{id}`. The list's view
 * model is shared by the graph's screens, so the product's boxes follow the same filters and updates.
 */
fun NavGraphBuilder.cabinetGraph(
    navController: NavHostController,
    environment: AppEnvironment,
    onOpenScan: () -> Unit
) {
    navigation(route = CabinetRoutes.GRAPH, startDestination = CabinetRoutes.HOME) {
        composable(CabinetRoutes.HOME) { entry ->
            CabinetScreen(
                cabinetModel(navController, entry, environment),
                onOpenProduct = { row ->
                    navController.navigate(CabinetRoutes.route(CabinetRoutes.destination(row)))
                },
                onOpenScan = onOpenScan
            )
        }
        composable(
            CabinetRoutes.PRODUCT,
            arguments = listOf(
                navArgument(CabinetRoutes.CATEGORY_ARG) { type = NavType.StringType },
                navArgument(CabinetRoutes.KEY_ARG) { type = NavType.StringType }
            )
        ) { entry ->
            val category = entry.arguments?.getString(CabinetRoutes.CATEGORY_ARG).orEmpty()
            val key = entry.arguments?.getString(CabinetRoutes.KEY_ARG).orEmpty()
            val rowId = CabinetRoutes.rowId(category, key)
            if (rowId == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                ProductBoxesScreen(
                    cabinetModel(navController, entry, environment),
                    rowId,
                    environment.expiryStatus,
                    onOpenBox = { navController.navigate(CabinetRoutes.route(CabinetDestination.Box(it.id))) },
                    onBack = { navController.popBackStack() }
                )
            }
        }
        composable(
            CabinetRoutes.BOX,
            arguments = listOf(
                navArgument(CabinetRoutes.ID_ARG) {
                    type = NavType.StringType
                }
            )
        ) { entry ->
            val id = entry.arguments?.getString(CabinetRoutes.ID_ARG)?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
            if (id == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                val model: BoxDetailViewModel = viewModel(
                    factory = viewModelFactory { initializer { BoxDetailViewModel(id, environment) } }
                )
                BoxDetailScreen(model, environment.zone, onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun cabinetModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
    environment: AppEnvironment
): CabinetViewModel {
    val graph = remember(entry) { navController.getBackStackEntry(CabinetRoutes.GRAPH) }
    return viewModel(
        viewModelStoreOwner = graph,
        factory = viewModelFactory { initializer { CabinetViewModel(environment) } }
    )
}
