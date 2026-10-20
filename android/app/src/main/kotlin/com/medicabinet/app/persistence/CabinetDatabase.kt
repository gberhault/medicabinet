package com.medicabinet.app.persistence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The cabinet database on the platform SQLite. Opening it ([database]) creates the schema on first use and
 * runs pending migrations, inside one transaction, so a failing migration leaves the file unchanged.
 */
class CabinetDatabase(
    context: Context,
    name: String? = AppPaths.DATABASE_NAME,
    migrations: List<Migration> = Migrations.all
) : Closeable {
    private val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(MigratingCallback(migrations))
            .build()
    )

    /** Opens (and if needed creates or migrates) the database. Call off the main thread. */
    val database: SupportSQLiteDatabase get() = helper.writableDatabase

    private val changeCounter = MutableStateFlow(0L)

    /**
     * A counter bumped after every committed [transaction]: the observation flows of the repositories re-query when
     * it changes. Every write goes through [transaction], so none is missed.
     */
    val changes: StateFlow<Long> = changeCounter

    /** Runs [block] in one transaction; any exception rolls it back and is rethrown. */
    fun <T> transaction(block: (SupportSQLiteDatabase) -> T): T {
        val db = database
        db.beginTransaction()
        val result = try {
            block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
        changeCounter.update { it + 1 }
        return result
    }

    /** Runs [block] in one read transaction, so it sees one consistent state of the cabinet. Announces no change. */
    fun <T> read(block: (SupportSQLiteDatabase) -> T): T {
        val db = database
        db.beginTransactionReadOnly()
        try {
            return block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = helper.close()
}

/** Foreign keys and WAL on every connection; schema creation and upgrades from [migrations]. Never drops data. */
internal class MigratingCallback(private val migrations: List<Migration>) :
    SupportSQLiteOpenHelper.Callback(migrations.size) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SupportSQLiteDatabase) {
        migrations.forEach { it.apply(db) }
    }

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        migrations.subList(oldVersion, newVersion).forEach { it.apply(db) }
    }

    /**
     * The platform default deletes the database file when it looks corrupt. The cabinet is the user's data:
     * never delete it — fail loudly and leave the file for recovery instead.
     */
    override fun onCorruption(db: SupportSQLiteDatabase): Unit =
        throw CorruptDatabaseException("the cabinet database could not be read; it was left untouched")

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
        throw IllegalStateException(
            "database schema v$oldVersion is newer than this app (v$newVersion); refusing to downgrade"
        )
}

/** The database file exists but SQLite cannot read it. The file is never deleted. */
class CorruptDatabaseException(message: String) : IllegalStateException(message)
