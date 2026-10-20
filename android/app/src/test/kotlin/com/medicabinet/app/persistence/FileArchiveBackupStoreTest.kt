package com.medicabinet.app.persistence

import com.medicabinet.core.archive.ArchiveLimits
import com.medicabinet.core.archive.BackupReason
import com.medicabinet.core.archive.CabinetSummary
import com.medicabinet.core.testing.SyntheticArchive
import java.io.File
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The automatic backup files (design §1.3, §5.2). Plain files: no Android needed. */
class FileArchiveBackupStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val archive = SyntheticArchive.json(locations = 2, boxes = 3).toByteArray()
    private val takenAt = Instant.parse("2027-01-15T08:00:00.123Z")
    private val directory: File get() = File(temporary.root, "backups")
    private val store: FileArchiveBackupStore get() = FileArchiveBackupStore(directory)

    private fun names() = directory.list().orEmpty().sorted()

    @Test
    fun aBackupIsNamedByReasonAndUtcTimeAndReadsBack() = runBlocking {
        val entry = store.write(archive, BackupReason.BEFORE_IMPORT, takenAt)
        assertEquals("pre-import-20270115-080000-123.medicabinet", entry.id)
        assertEquals(CabinetSummary(2, 3, 0, 0, 0), entry.summary)
        assertEquals(listOf(entry.id), names(), "no partial file is left behind")
        assertEquals(listOf(entry), store.list())
        assertContentEquals(archive, store.read(entry.id))
    }

    @Test
    fun aRestoreBackupHasItsOwnPrefix() = runBlocking {
        val entry = store.write(archive, BackupReason.BEFORE_RESTORE, takenAt)
        assertEquals("pre-restore-20270115-080000-123.medicabinet", entry.id)
        assertEquals(BackupReason.BEFORE_RESTORE, store.list().single().reason)
        assertEquals(takenAt, store.list().single().takenAt)
    }

    @Test
    fun somethingThatDoesNotReadBackAsAnArchiveIsNotKept() {
        assertFailsWith<ArchiveBackupException> {
            store.write("{".toByteArray(), BackupReason.BEFORE_IMPORT, takenAt)
        }
        assertEquals(emptyList(), names())
    }

    @Test
    fun aFolderThatCannotBeCreatedFailsTheWrite() {
        directory.writeText("a file where the folder should be")
        assertFailsWith<ArchiveBackupException> { store.write(archive, BackupReason.BEFORE_IMPORT, takenAt) }
    }

    @Test
    fun twoBackupsInTheSameMillisecondAreBothKept() = runBlocking {
        val first = store.write(archive, BackupReason.BEFORE_IMPORT, takenAt)
        val second = store.write(archive, BackupReason.BEFORE_IMPORT, takenAt)
        assertEquals("pre-import-20270115-080000-123-2.medicabinet", second.id)
        assertEquals(setOf(first.id, second.id), store.list().map { it.id }.toSet())
    }

    @Test
    fun anUnreadableBackupIsListedNotHidden() = runBlocking {
        directory.mkdirs()
        File(directory, "pre-import-20270101-000000-000.medicabinet").writeText("damaged")
        val listed = store.list().single()
        assertNull(listed.summary)
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), listed.takenAt)
    }

    @Test
    fun aFileGrownBeyondTheSizeLimitIsListedAsUnreadable() = runBlocking {
        store.write(archive, BackupReason.BEFORE_IMPORT, takenAt)
        val small = FileArchiveBackupStore(directory, ArchiveLimits(maxFileBytes = 100))
        assertNull(small.list().single().summary)
    }

    @Test
    fun backupsAreListedNewestFirstAndPrunedToThreeOverBothPrefixes() = runBlocking {
        val times = (0L until 5L).map { takenAt.plusSeconds(it * 60) }
        times.forEachIndexed { index, time ->
            store.write(archive, if (index % 2 == 0) BackupReason.BEFORE_IMPORT else BackupReason.BEFORE_RESTORE, time)
        }
        assertEquals(times.reversed(), store.list().map { it.takenAt })

        store.prune(3)

        assertEquals(times.takeLast(3).reversed(), store.list().map { it.takenAt })
    }

    @Test
    fun preMigrationCopiesAndOtherFilesAreNeverListedOrPruned() = runBlocking {
        directory.mkdirs()
        val untouched = listOf(
            "pre-migration-20260101-000000-v1.db",
            "pre-import-20270101-000000-000.medicabinet.partial",
            "notes.txt",
            "pre-import-garbage.medicabinet"
        )
        untouched.forEach { File(directory, it).writeText("x") }
        store.write(archive, BackupReason.BEFORE_IMPORT, takenAt)

        assertEquals(1, store.list().size)
        store.prune(0)

        assertEquals(untouched.sorted(), names())
    }

    @Test
    fun aNegativeKeepCountDeletesEveryBackup() = runBlocking {
        store.write(archive, BackupReason.BEFORE_IMPORT, takenAt)
        store.prune(-1)
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun onlyAListedBackupCanBeRead() {
        runBlocking {
            directory.mkdirs()
            File(temporary.root, "cabinet.db").writeText("secret")
            for (id in listOf("../cabinet.db", "missing.medicabinet", "pre-import-20270101-000000-000.medicabinet")) {
                assertFailsWith<ArchiveBackupException>(id) { store.read(id) }
            }
        }
    }

    @Test
    fun anEmptyOrMissingFolderListsNothing() = runBlocking {
        assertTrue(store.list().isEmpty())
        store.prune(3)
        assertTrue(store.list().isEmpty())
    }
}
