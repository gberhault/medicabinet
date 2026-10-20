package com.medicabinet.app.features.transfer

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.medicabinet.app.app.AppEnvironment
import java.time.Instant

/** The screens of Settings → **Backup and transfer**. */
object TransferRoutes {
    const val IMPORT = "settings/import"
    const val BACKUPS = "settings/backups"
    private const val ID = "id"
    private const val TAKEN_AT = "takenAt"
    internal const val RESTORE = "settings/restore/{$ID}/{$TAKEN_AT}"

    /** Backup ids are file names (letters, digits, `-`, `.`); encoded anyway. */
    internal fun restore(id: String, takenAt: Instant) = "settings/restore/${Uri.encode(id)}/${takenAt.toEpochMilli()}"

    internal val restoreArguments = listOf(
        navArgument(ID) { type = NavType.StringType },
        navArgument(TAKEN_AT) { type = NavType.LongType }
    )

    internal fun backupOf(arguments: android.os.Bundle?): ImportRequest.Backup = ImportRequest.Backup(
        arguments?.getString(ID).orEmpty(),
        Instant.ofEpochMilli(arguments?.getLong(TAKEN_AT) ?: 0L)
    )
}

/** Import (from the coordinator), the automatic backups, and a restore (the import flow on a backup). */
fun NavGraphBuilder.transferRoutes(navController: NavHostController, environment: AppEnvironment) {
    composable(TransferRoutes.BACKUPS) {
        BackupsRoute(
            environment,
            onRestore = { entry -> navController.navigate(TransferRoutes.restore(entry.id, entry.takenAt)) },
            onBack = { navController.popBackStack() }
        )
    }
    composable(TransferRoutes.RESTORE, arguments = TransferRoutes.restoreArguments) { entry ->
        val request = TransferRoutes.backupOf(entry.arguments)
        ImportRoute(environment, request = { request }, onClose = { navController.popBackStack() })
    }
    composable(TransferRoutes.IMPORT) {
        val coordinator = LocalImportCoordinator.current
        ImportRoute(
            environment,
            request = { ImportRequest.File(coordinator?.take()) },
            onClose = { navController.popBackStack() }
        )
    }
}
