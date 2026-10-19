package com.medicabinet.app.persistence

import android.database.sqlite.SQLiteDatabase
import com.medicabinet.core.Clock
import java.io.File
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The pre-migration copy could not be made, so nothing may be migrated. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Copies the cabinet database before pending migrations run on an existing database, so an update can
 * never cost the user their data. Run it off the main thread, before [CabinetDatabase] is opened.
 *
 * @param schemaVersion the version this build of the app migrates to (`Migrations.all.size`)
 */
class BackupService(
    private val databaseFile: File,
    private val directory: File,
    private val clock: Clock,
    private val schemaVersion: Int = Migrations.all.size
) {
    /**
     * Returns the backup file, or `null` when none is needed: the database is brand new (nothing to lose)
     * or has no pending migration.
     * @throws BackupException when a needed copy cannot be written
     */
    fun backupIfNeeded(): File? {
        val version = existingVersion()
        return if (version != null && version in 1 until schemaVersion) copyBeforeMigrating(version) else null
    }

    private fun copyBeforeMigrating(version: Int): File {
        try {
            check(directory.isDirectory || directory.mkdirs()) { "cannot create ${directory.name}" }
            val target = uniqueTarget(version)
            val partial = File(directory, target.name + PARTIAL_SUFFIX)
            try {
                databaseFile.copyTo(partial, overwrite = true)
                check(partial.renameTo(target)) { "cannot finish ${target.name}" }
            } finally {
                partial.delete()
            }
            return target
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            throw BackupException("pre-migration backup failed", error)
        }
    }

    /** Deletes all but the [count] newest pre-migration backups (ordered by the timestamp in their name). */
    fun prune(count: Int = DEFAULT_KEEP_COUNT) {
        val backups = directory.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(EXTENSION) }
            ?.sortedBy { it.name }
            ?: return
        backups.dropLast(count.coerceAtLeast(0)).forEach { it.delete() }
    }

    /**
     * `PRAGMA user_version` of the existing database file, or `null` when there is none. A WAL checkpoint
     * folds recent writes into the main file first, so copying that single file is a complete copy.
     */
    private fun existingVersion(): Int? {
        if (!databaseFile.isFile) return null
        val database = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            return database.version
        } finally {
            database.close()
        }
    }

    private fun uniqueTarget(version: Int): File {
        val base = "$PREFIX${FORMAT.format(clock.now())}-v$version"
        var candidate = File(directory, "$base$EXTENSION")
        var counter = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base-$counter$EXTENSION")
            counter++
        }
        return candidate
    }

    companion object {
        const val PREFIX = "pre-migration-"
        const val EXTENSION = ".db"
        const val DEFAULT_KEEP_COUNT = 5
        private const val PARTIAL_SUFFIX = ".partial"
        private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
