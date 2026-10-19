package com.medicabinet.app.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.core.testing.MutableClock
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000)) // 2027-01-15 08:00:00 UTC
    private val name = "backup-${UUID.randomUUID()}.db"
    private val databaseFile: File get() = context.getDatabasePath(name)
    private val directory: File get() = File(context.filesDir, "backups-${name.removeSuffix(".db")}")
    private val opened = mutableListOf<CabinetDatabase>()

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        directory.deleteRecursively()
    }

    private fun service(schemaVersion: Int = 2) = BackupService(databaseFile, directory, clock, schemaVersion)

    /** An on-disk cabinet migrated to v1 holding one location; left open so recent writes sit in the WAL. */
    private fun existingCabinet(keepOpen: Boolean): CabinetDatabase {
        val cabinet = CabinetDatabase(context, name)
        cabinet.database.execSQL(
            "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) " +
                "VALUES ('L', 'Kept', 0, 0, '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
        )
        if (keepOpen) opened += cabinet else cabinet.close()
        return cabinet
    }

    private fun backupFiles(): List<String> = directory.list()?.sorted() ?: emptyList()

    @Test
    fun aBrandNewDatabaseNeedsNoBackup() {
        assertNull(service().backupIfNeeded())
        assertFalse(directory.exists())
    }

    @Test
    fun anEmptyFileAtVersionZeroIsTreatedAsNew() {
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).close()
        assertNull(service().backupIfNeeded())
        assertTrue(backupFiles().isEmpty())
    }

    @Test
    fun aFullyMigratedDatabaseNeedsNoBackup() {
        existingCabinet(keepOpen = false)
        assertNull(service(schemaVersion = 1).backupIfNeeded())
        assertTrue(backupFiles().isEmpty())
    }

    @Test
    fun aPendingMigrationOnAnExistingDatabaseIsBackedUpFirst() {
        existingCabinet(keepOpen = false)
        val backup = assertNotNull(service().backupIfNeeded())

        assertEquals("pre-migration-20270115-080000-v1.db", backup.name)
        val copy = SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY)
        copy.use {
            assertEquals(1, it.version)
            it.rawQuery("SELECT name FROM location", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Kept", cursor.getString(0))
            }
        }
        assertEquals(listOf(backup.name), backupFiles(), "a single self-contained file, no side files")
    }

    @Test
    fun recentWritesStillInTheWriteAheadLogAreIncludedInTheCopy() {
        existingCabinet(keepOpen = true)
        val backup = assertNotNull(service().backupIfNeeded())
        SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT COUNT(*) FROM location", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun twoBackupsInTheSameSecondDoNotOverwriteEachOther() {
        existingCabinet(keepOpen = false)
        val first = assertNotNull(service().backupIfNeeded())
        val second = assertNotNull(service().backupIfNeeded())
        assertNotEquals(first, second)
        assertEquals(2, backupFiles().size)
    }

    @Test
    fun anUnwritableBackupFolderFailsAndLeavesTheDatabaseUntouched() {
        existingCabinet(keepOpen = false)
        val before = databaseFile.readBytes().size
        directory.parentFile?.mkdirs()
        directory.writeText("a file where the Backups folder should be")
        assertFailsWith<BackupException> { service().backupIfNeeded() }
        assertTrue(databaseFile.isFile)
        assertEquals(before, databaseFile.readBytes().size)
        directory.delete()
    }

    @Test
    fun namesTakenByOtherEntriesAreSkippedAndNoPartialFileIsLeft() {
        existingCabinet(keepOpen = false)
        // The first two candidate names are already taken, so the copy goes to the next free one.
        File(directory, "pre-migration-20270115-080000-v1.db").mkdirs()
        File(directory, "pre-migration-20270115-080000-v1-2.db").mkdirs()
        val backup = assertNotNull(service().backupIfNeeded())
        assertEquals("pre-migration-20270115-080000-v1-3.db", backup.name)
        assertTrue(backupFiles().none { it.endsWith(".partial") })
    }

    @Test
    fun pruningKeepsTheNewestFiveAndIgnoresOtherFiles() {
        directory.mkdirs()
        val names = (1..7).map { "pre-migration-2027010$it-080000-v1.db" }
        names.forEach { File(directory, it).writeBytes(ByteArray(0)) }
        File(directory, "notes.txt").writeText("keep me")

        service().prune(count = 5)

        assertEquals(listOf("notes.txt") + names.takeLast(5), backupFiles())
    }

    @Test
    fun pruningWithoutAFolderOrWithFewFilesIsHarmless() {
        service().prune()
        directory.mkdirs()
        File(directory, "pre-migration-20270101-080000-v1.db").writeBytes(ByteArray(0))
        service().prune()
        assertEquals(1, backupFiles().size)
    }
}
