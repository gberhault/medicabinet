package com.medicabinet.app.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.app.app.BootstrapFailure
import com.medicabinet.app.app.BootstrapState
import com.medicabinet.core.Clock
import com.medicabinet.core.Location
import com.medicabinet.core.testing.MutableClock
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BootstrapTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
    private val name = "bootstrap-${UUID.randomUUID()}.db"
    private val databaseFile: File get() = context.getDatabasePath(name)
    private val backups: File get() = AppPaths(context).backupsDirectory
    private val createLater = Migration("v3", "CREATE TABLE later (id INTEGER NOT NULL)")

    @After
    fun cleanUp() {
        backups.deleteRecursively()
    }

    private fun bootstrap(migrations: List<Migration> = Migrations.all, clock: Clock = this.clock) =
        Bootstrap(context, defaultLocationName = "Armoire", migrations = migrations, clock = clock, databaseName = name)

    private fun ready(state: BootstrapState) = assertIs<BootstrapState.Ready>(state).environment

    private fun failure(state: BootstrapState) = assertIs<BootstrapState.Failed>(state).failure

    private fun backupNames(): List<String> = backups.list()?.sorted() ?: emptyList()

    private fun tableNames(): Set<String> =
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
            }
        }

    // --- first launch and upgrade ---

    @Test
    fun firstLaunchMigratesCreatesTheDefaultLocationAndTakesNoBackup() = runBlocking<Unit> {
        val environment = ready(bootstrap().run())
        val all = environment.locations.all()
        assertEquals(1, all.size)
        assertTrue(all.single().isDefault)
        assertEquals("Armoire", all.single().name)
        assertTrue(backupNames().isEmpty())
    }

    @Test
    fun relaunchingKeepsASingleDefaultLocation() = runBlocking<Unit> {
        ready(bootstrap().run())
        val again = ready(bootstrap().run())
        assertEquals(1, again.locations.all().size)
        assertTrue(backupNames().isEmpty())
    }

    @Test
    fun anUpgradeBacksUpThenMigratesAndKeepsTheData() = runBlocking<Unit> {
        val first = ready(bootstrap().run())
        first.locations.insert(Location(name = "Kitchen", createdAt = clock.now()))

        val second = ready(bootstrap(Migrations.all + createLater).run())

        assertEquals(1, backupNames().size)
        assertEquals(listOf("Armoire", "Kitchen"), second.locations.all().map { it.name }.sorted())
        assertTrue("later" in tableNames())
    }

    @Test
    fun oldBackupsArePrunedAfterASuccessfulUpgrade() = runBlocking<Unit> {
        ready(bootstrap().run())
        backups.mkdirs()
        (1..6).forEach { File(backups, "pre-migration-2026010$it-080000-v1.db").writeBytes(ByteArray(0)) }

        ready(bootstrap(Migrations.all + createLater).run())

        assertEquals(5, backupNames().size)
        assertTrue(backupNames().any { it.startsWith("pre-migration-20270115") })
    }

    // --- failures ---

    @Test
    fun aFailingMigrationLeavesTheDatabaseAsItWasAndKeepsTheBackup() = runBlocking<Unit> {
        val first = ready(bootstrap().run())
        first.locations.insert(Location(name = "Kitchen", createdAt = clock.now()))
        val broken = Migration("v3", "CREATE TABLE half_done (id INTEGER); THIS IS NOT SQL")

        assertEquals(BootstrapFailure.MIGRATION, failure(bootstrap(Migrations.all + broken).run()))

        assertFalse("half_done" in tableNames())
        val after = ready(bootstrap().run())
        assertEquals(listOf("Armoire", "Kitchen"), after.locations.all().map { it.name }.sorted())
        assertEquals(1, backupNames().size)
    }

    @Test
    fun retryingAfterAFailedMigrationSucceedsOnceFixed() = runBlocking<Unit> {
        ready(bootstrap().run())
        assertEquals(
            BootstrapFailure.MIGRATION,
            failure(bootstrap(Migrations.all + Migration("v3", "NOT SQL")).run())
        )
        ready(bootstrap(Migrations.all + createLater).run())
        assertTrue("later" in tableNames())
    }

    @Test
    fun aFailedFirstLaunchLeavesAnEmptyDatabaseThatRetryMigrates() = runBlocking<Unit> {
        assertEquals(BootstrapFailure.MIGRATION, failure(bootstrap(listOf(Migration("v1", "NOT SQL"))).run()))
        assertTrue(backupNames().isEmpty())
        ready(bootstrap().run())
        assertTrue("box" in tableNames())
    }

    @Test
    fun anUnwritableBackupFolderStopsBeforeAnyMigration() = runBlocking<Unit> {
        ready(bootstrap().run())
        backups.deleteRecursively()
        backups.parentFile?.mkdirs()
        backups.writeText("a file where the backups folder should be")

        assertEquals(BootstrapFailure.BACKUP, failure(bootstrap(Migrations.all + createLater).run()))

        assertFalse("later" in tableNames())
        backups.delete()
    }

    @Test
    fun aCorruptDatabaseIsReportedAndNeverDeleted() = runBlocking<Unit> {
        databaseFile.parentFile?.mkdirs()
        val garbage = ByteArray(4096) { (it * 31).toByte() }
        databaseFile.writeBytes(garbage)

        assertEquals(BootstrapFailure.STORAGE, failure(bootstrap().run()))

        assertTrue(databaseFile.isFile, "the unreadable database must not be deleted")
        assertContentEquals(garbage, databaseFile.readBytes())
    }

    // --- restore ---

    @Test
    fun restorePutsTheBackupBackAsTheDatabase() = runBlocking<Unit> {
        val first = ready(bootstrap().run())
        first.locations.insert(Location(name = "Kitchen", createdAt = clock.now()))
        val service = BackupService(databaseFile, backups, clock, schemaVersion = 3)
        val backup = checkNotNull(service.backupIfNeeded())

        // Damage the live database, then restore.
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM location")
            it.execSQL("CREATE TABLE junk (x INTEGER)")
        }
        service.restore(backup)

        assertFalse("junk" in tableNames())
        val restored = ready(bootstrap().run())
        assertEquals(listOf("Armoire", "Kitchen"), restored.locations.all().map { it.name }.sorted())
        assertTrue(backup.isFile, "the backup stays in the backups folder")
    }

    // --- threading ---

    @Test
    fun bootstrapWorkRunsOffTheMainThread() = runBlocking<Unit> {
        val threads = mutableListOf<Thread>()
        val recording = Clock { clock.now().also { threads += Thread.currentThread() } }
        ready(bootstrap(clock = recording).run())
        assertTrue(threads.isNotEmpty())
        assertTrue(threads.none { it == Looper.getMainLooper().thread }, "work ran on the main thread")
        assertNotEquals(Thread.currentThread(), threads.first())
    }
}
