package com.medicabinet.app.persistence

import android.content.Context
import java.io.File

/** Where the cabinet lives: the app's internal storage only, never external or shared storage. */
class AppPaths(private val context: Context) {
    val databaseFile: File get() = context.getDatabasePath(DATABASE_NAME)

    /** Pre-migration copies; included in Android Auto Backup with the database (see backup rules). */
    val backupsDirectory: File get() = File(context.filesDir, BACKUPS_DIRECTORY)

    companion object {
        const val DATABASE_NAME = "cabinet.db"
        const val BACKUPS_DIRECTORY = "backups"
    }
}
