package com.medicabinet.app.persistence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** Opens the cabinet database with the settings its constraints rely on. */
object DatabaseFactory {
    /**
     * Creates the schema on first open and migrates it on upgrade. Foreign keys are switched on for every
     * connection (SQLite ignores them otherwise) and write-ahead logging is enabled, as on iOS.
     */
    fun openDriver(
        context: Context,
        name: String? = AppPaths.DATABASE_NAME,
        schema: SqlSchema<QueryResult.Value<Unit>> = AppDatabase.Schema
    ): SqlDriver = AndroidSqliteDriver(
        schema = schema,
        context = context,
        name = name,
        callback = object : AndroidSqliteDriver.Callback(schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
                db.enableWriteAheadLogging()
            }
        }
    )
}
