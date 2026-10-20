package com.medicabinet.app.persistence

import android.content.Context
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.app.AppLog
import com.medicabinet.app.app.BootstrapFailure
import com.medicabinet.app.app.BootstrapState
import com.medicabinet.app.app.DayTicker
import com.medicabinet.app.app.ExpiryPreferences
import com.medicabinet.app.app.SharedExpiryPreferences
import com.medicabinet.app.app.systemTimeChanges
import com.medicabinet.app.features.settings.PackageAppInfo
import com.medicabinet.app.features.settings.archiveApp
import com.medicabinet.app.persistence.reference.AndroidReferenceAssets
import com.medicabinet.app.persistence.reference.ReferenceInstaller
import com.medicabinet.app.persistence.reference.SqlReferenceData
import com.medicabinet.core.Clock
import com.medicabinet.core.SystemClock
import com.medicabinet.core.archive.ArchiveApp
import com.medicabinet.core.archive.CabinetTransfer
import com.medicabinet.core.reference.ReferenceDataSource
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the cabinet, backs it up when migrations are pending, migrates, makes sure a default location exists.
 * Never deletes the database; a failure leaves the user's data as it was. Runs off the main thread.
 */
class Bootstrap(
    private val context: Context,
    private val defaultLocationName: String,
    private val migrations: List<Migration> = Migrations.all,
    private val clock: Clock = SystemClock,
    private val backupsToKeep: Int = BackupService.DEFAULT_KEEP_COUNT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val databaseName: String = AppPaths.DATABASE_NAME,
    private val expiryPreferences: ExpiryPreferences = SharedExpiryPreferences(context),
    /** Only handed to the environment here: it installs and opens the reference data on first use, not at launch. */
    private val reference: ReferenceDataSource = SqlReferenceData(
        ReferenceInstaller(AndroidReferenceAssets(context.assets), AppPaths(context).referenceDirectory),
        io
    ),
    /** Who writes the archives this cabinet exports (and its automatic backups). */
    private val archiveApp: ArchiveApp = PackageAppInfo(context).archiveApp()
) {
    suspend fun run(): BootstrapState = withContext(io) {
        try {
            BootstrapState.Ready(open())
        } catch (abort: BootstrapAbort) {
            BootstrapState.Failed(abort.failure)
        }
    }

    private class BootstrapAbort(val failure: BootstrapFailure) : Exception()

    private suspend fun open(): AppEnvironment {
        val paths = AppPaths(context)
        clearExports(paths)
        val backups = BackupService(
            context.getDatabasePath(databaseName),
            paths.backupsDirectory,
            clock,
            migrations.size
        )
        val backup = backupBeforeMigrating(backups)
        val cabinet = migrate(backups, backup)
        val locations = SqlLocationRepository(cabinet, clock, io)
        ensureDefaultLocation(cabinet, locations)
        runCatching { backups.prune(backupsToKeep) }
        return AppEnvironment(
            locations,
            SqlBoxRepository(cabinet, clock, io),
            SqlNamedProductRepository(cabinet, clock, io),
            clock,
            reference,
            expiryPreferences = expiryPreferences,
            // The zone is read at each use and a time-zone or clock change wakes the ticker (S9).
            todaySource = DayTicker(clock, { ZoneId.systemDefault() }, systemTimeChanges(context)),
            transfer = CabinetTransfer(
                SqlCabinetArchiveStore(cabinet, io),
                FileArchiveBackupStore(paths.backupsDirectory, io = io),
                clock,
                archiveApp
            )
        )
    }

    /**
     * A file shared at the last launch is no longer needed (the share sheet never says when the receiving app has read
     * it, E6). Best effort: a file left behind stays in the private cache and is retried at the next launch.
     */
    private fun clearExports(paths: AppPaths) {
        if (!paths.exportDirectory.deleteRecursively()) AppLog.transferFailed("launch-cleanup", null)
    }

    /** A needed copy that cannot be written stops everything: nothing is migrated without a safety copy. */
    @Suppress("TooGenericExceptionCaught")
    private fun backupBeforeMigrating(backups: BackupService): File? = try {
        backups.backupIfNeeded()
    } catch (error: BackupException) {
        AppLog.bootstrapFailed("backup", error)
        throw BootstrapAbort(BootstrapFailure.BACKUP)
    } catch (error: Exception) {
        AppLog.bootstrapFailed("storage", error)
        throw BootstrapAbort(BootstrapFailure.STORAGE)
    }

    /** Opening runs the pending migrations in one transaction; on failure the pre-migration copy is put back. */
    @Suppress("TooGenericExceptionCaught")
    private fun migrate(backups: BackupService, backup: File?): CabinetDatabase {
        val cabinet = CabinetDatabase(context, databaseName, migrations)
        try {
            cabinet.database
        } catch (error: Exception) {
            AppLog.bootstrapFailed("migration", error)
            cabinet.close()
            if (backup != null) restoreQuietly(backups, backup)
            throw BootstrapAbort(if (isStorageProblem(error)) BootstrapFailure.STORAGE else BootstrapFailure.MIGRATION)
        }
        return cabinet
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun ensureDefaultLocation(cabinet: CabinetDatabase, locations: SqlLocationRepository) {
        try {
            locations.ensureDefault(defaultLocationName)
        } catch (error: Exception) {
            AppLog.bootstrapFailed("default-location", error)
            cabinet.close()
            throw BootstrapAbort(BootstrapFailure.STORAGE)
        }
    }

    /** Puts the pre-migration copy back; the copy itself stays in the backups folder either way. */
    @Suppress("TooGenericExceptionCaught")
    private fun restoreQuietly(backups: BackupService, backup: File) {
        try {
            backups.restore(backup)
        } catch (error: Exception) {
            AppLog.bootstrapFailed("restore", error)
        }
    }

    private fun isStorageProblem(error: Exception): Boolean = error is SQLiteFullException ||
        error is SQLiteDiskIOException ||
        error is SQLiteCantOpenDatabaseException ||
        error is CorruptDatabaseException
}
