package com.medicabinet.core.archive

import com.medicabinet.core.Box
import com.medicabinet.core.FixedClock
import com.medicabinet.core.Location
import com.medicabinet.core.ProductRef
import com.medicabinet.core.testing.InMemoryArchiveBackupStore
import com.medicabinet.core.testing.InMemoryArchiveStore
import com.medicabinet.core.testing.SharedFixtures
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class CabinetTransferTest {
    private val app = ArchiveApp("MediCabinet", "ios", "0.3.0", "3")
    private val exportedAt = ArchiveDates.instant(1_792_397_730_123L)

    private class Setup(
        val store: InMemoryArchiveStore,
        val backups: InMemoryArchiveBackupStore,
        val transfer: CabinetTransfer,
        val golden: ByteArray,
        val goldenCabinet: CabinetSnapshot
    )

    /** A transfer whose store already holds [initial] (nothing when `null`). */
    private fun setup(initial: CabinetSnapshot? = null, store: CabinetArchiveStore? = null): Setup {
        val golden = SharedFixtures.goldenArchive()
        val goldenCabinet = ArchiveDecoder.decode(golden).cabinet
        val memory = InMemoryArchiveStore(initial ?: CabinetSnapshot(emptyList(), emptyList()))
        val backups = InMemoryArchiveBackupStore()
        val transfer = CabinetTransfer(store ?: memory, backups, FixedClock(exportedAt), app)
        return Setup(memory, backups, transfer, golden, goldenCabinet)
    }

    private fun smallCabinet(name: String): CabinetSnapshot {
        val location = Location(name = name, isDefault = true, createdAt = exportedAt)
        val box = Box(product = ProductRef(name = "Only box"), locationId = location.id, createdAt = exportedAt)
        return CabinetSnapshot(listOf(location), listOf(ArchivedBox(box)))
    }

    private fun refusingStore() = object : CabinetArchiveStore {
        override suspend fun snapshot() = CabinetSnapshot(emptyList(), emptyList())

        override suspend fun replace(snapshot: CabinetSnapshot, beforeReplacing: (CabinetSnapshot) -> Unit) =
            throw IllegalStateException("disk full")
    }

    // MARK: Export

    @Test
    fun exportingTheGoldenCabinetGivesTheGoldenFileByteForByte() = runBlocking {
        val context = setup()
        context.store.replace(context.goldenCabinet) { }
        assertContentEquals(context.golden, context.transfer.exportArchive())
    }

    @Test
    fun anEmptyCabinetCanBeExported() = runBlocking {
        val home = Location(name = "Home", isDefault = true, createdAt = exportedAt)
        val context = setup(CabinetSnapshot(listOf(home), emptyList()))
        val archive = ArchiveDecoder.decode(context.transfer.exportArchive())
        assertEquals(0, archive.cabinet.summary.inCabinet)
        assertEquals(app, archive.app)
    }

    @Test
    fun aCabinetTheArchiveWouldRefuseIsNotExported() = runBlocking {
        val context = setup(CabinetSnapshot(emptyList(), emptyList()))
        val error = assertFailsWith<ArchiveException> { context.transfer.exportArchive() }
        assertEquals(ArchiveErrorKind.DEFAULT_LOCATION, error.kind)
    }

    @Test
    fun theCurrentSummaryDescribesTheStore() = runBlocking {
        val context = setup(smallCabinet("Home"))
        assertEquals(CabinetSummary(1, 1, 0, 0, 1), context.transfer.currentSummary())
    }

    // MARK: Preview

    @Test
    fun aPreviewComparesTheFileWithThePhone() = runBlocking {
        val context = setup(smallCabinet("Home"))
        val preview = context.transfer.preview(context.golden)
        assertEquals(context.goldenCabinet.summary, preview.file)
        assertEquals(1, preview.current.inCabinet)
        assertTrue(preview.willBackUp)
        assertEquals(1, preview.archive.sourceFormatVersion)
    }

    @Test
    fun aPhoneWithNothingToLoseNeedsNoBackup() = runBlocking {
        val golden = SharedFixtures.goldenArchive()
        val home = Location(name = "Home", isDefault = true, createdAt = exportedAt)
        assertFalse(setup(CabinetSnapshot(listOf(home), emptyList())).transfer.preview(golden).willBackUp)
        assertFalse(setup().transfer.preview(golden).willBackUp)
    }

    @Test
    fun aFileTheDecoderRefusesIsNotPreviewed() = runBlocking {
        val context = setup()
        val notArchive = assertFailsWith<ArchiveException> { context.transfer.preview("{}".toByteArray()) }
        assertEquals(ArchiveErrorKind.NOT_ARCHIVE, notArchive.kind)
        val notJson = assertFailsWith<ArchiveException> { context.transfer.preview(ByteArray(0)) }
        assertEquals(ArchiveErrorKind.NOT_JSON, notJson.kind)
    }

    // MARK: Apply

    @Test
    fun applyBacksUpTheOldCabinetThenReplacesIt() = runBlocking {
        val old = smallCabinet("Old")
        val context = setup(old)
        val result = context.transfer.apply(context.transfer.preview(context.golden), BackupReason.BEFORE_IMPORT)
        assertEquals(context.goldenCabinet, context.store.snapshot())
        assertEquals(context.goldenCabinet.summary, result.imported)
        val backup = assertNotNull(result.backup)
        assertEquals(BackupReason.BEFORE_IMPORT, backup.reason)
        assertEquals(exportedAt, backup.takenAt)
        val saved = ArchiveDecoder.decode(context.backups.read(backup.id))
        assertEquals(old, saved.cabinet)
        assertEquals(exportedAt, saved.exportedAt)
        assertEquals(app, saved.app)
        assertFalse(result.pruneFailed)
    }

    @Test
    fun nothingIsBackedUpWhenThereIsNothingToLose() = runBlocking {
        val context = setup()
        val result = context.transfer.apply(context.transfer.preview(context.golden), BackupReason.BEFORE_IMPORT)
        assertNull(result.backup)
        assertEquals(0, context.backups.count)
        assertEquals(context.goldenCabinet, context.store.snapshot())
    }

    @Test
    fun theBackupHoldsTheCabinetAsItIsWhenTheReplaceStartsNotWhenTheFileWasPreviewed() = runBlocking {
        val context = setup(smallCabinet("At preview time"))
        val preview = context.transfer.preview(context.golden)
        val later = smallCabinet("Added after the preview")
        context.store.replace(later) { }
        val result = context.transfer.apply(preview, BackupReason.BEFORE_IMPORT)
        val saved = ArchiveDecoder.decode(context.backups.read(assertNotNull(result.backup).id))
        assertEquals(later, saved.cabinet)
    }

    @Test
    fun aFailedBackupStopsTheImportAndLeavesTheCabinetAlone() = runBlocking {
        val old = smallCabinet("Old")
        val context = setup(old)
        val preview = context.transfer.preview(context.golden)
        context.backups.setFailingWrites(true)
        val error = assertFailsWith<TransferException> { context.transfer.apply(preview, BackupReason.BEFORE_IMPORT) }
        assertEquals(TransferErrorKind.BACKUP_FAILED, error.kind)
        assertEquals(old, context.store.snapshot())
        assertEquals(0, context.backups.count)
    }

    @Test
    fun aCabinetThatCannotBeEncodedStopsTheImportToo() = runBlocking {
        // Two default locations: the store holds it, no archive can, so no backup can be made. Refuse, don't go on.
        val first = Location(name = "A", isDefault = true, createdAt = exportedAt)
        val second = Location(name = "B", isDefault = false, createdAt = exportedAt)
        val broken = CabinetSnapshot(listOf(first, second.copy(isDefault = true)), emptyList())
        val context = setup(broken)
        val preview = context.transfer.preview(context.golden)
        val error = assertFailsWith<TransferException> { context.transfer.apply(preview, BackupReason.BEFORE_IMPORT) }
        assertEquals(TransferErrorKind.BACKUP_FAILED, error.kind)
        assertEquals(broken, context.store.snapshot())
    }

    @Test
    fun aStoreThatRefusesTheReplaceLeavesTheCabinetAlone() = runBlocking {
        val old = smallCabinet("Old")
        val context = setup(old)
        val preview = context.transfer.preview(context.golden)
        val refusing = CabinetTransfer(refusingStore(), context.backups, FixedClock(exportedAt), app)
        assertFailsWith<IllegalStateException> { refusing.apply(preview, BackupReason.BEFORE_IMPORT) }
        assertEquals(old, context.store.snapshot())
        assertEquals(0, context.backups.count)
    }

    @Test
    fun failingToPruneOldBackupsDoesNotFailTheImport() = runBlocking {
        val context = setup(smallCabinet("Old"))
        context.backups.setFailingPrunes(true)
        val result = context.transfer.apply(context.transfer.preview(context.golden), BackupReason.BEFORE_IMPORT)
        assertTrue(result.pruneFailed)
        assertEquals(context.goldenCabinet, context.store.snapshot())
    }

    @Test
    fun onlyTheNewestBackupsAreKept() = runBlocking {
        val context = setup(smallCabinet("Zero"))
        for (number in 1..5) {
            context.transfer.apply(context.transfer.preview(context.golden), BackupReason.BEFORE_IMPORT)
            // Put something else in the cabinet so the next import has something to back up.
            context.store.replace(smallCabinet("Round $number")) { }
        }
        assertEquals(CabinetTransfer.DEFAULT_KEEP_COUNT, context.transfer.backups().size)
    }

    // MARK: Restore

    @Test
    fun restoringABackupBringsTheOldCabinetBackAndBacksUpTheCurrentOne() = runBlocking {
        val old = smallCabinet("Before the import")
        val context = setup(old)
        val imported = context.transfer.apply(context.transfer.preview(context.golden), BackupReason.BEFORE_IMPORT)
        val backup = assertNotNull(imported.backup)

        val preview = context.transfer.preview(context.transfer.readBackup(backup.id))
        val restored = context.transfer.apply(preview, BackupReason.BEFORE_RESTORE)

        assertEquals(old, context.store.snapshot())
        assertEquals(BackupReason.BEFORE_RESTORE, assertNotNull(restored.backup).reason)
        val reasons = context.transfer.backups().map { it.reason }.toSet()
        assertEquals(setOf(BackupReason.BEFORE_IMPORT, BackupReason.BEFORE_RESTORE), reasons)
    }

    // MARK: File name, summary

    @TestFactory
    fun theFileNameUsesTheLocalDayOfTheExport(): List<DynamicTest> = listOf(
        Triple("2026-10-19T08:15:30.123Z", "Europe/Paris", "MediCabinet-2026-10-19.medicabinet"),
        Triple("2026-10-19T23:30:00.000Z", "Europe/Paris", "MediCabinet-2026-10-20.medicabinet"),
        Triple("2026-10-19T23:30:00.000Z", "America/Los_Angeles", "MediCabinet-2026-10-19.medicabinet"),
        Triple("2026-12-31T23:30:00.000Z", "UTC", "MediCabinet-2026-12-31.medicabinet"),
        Triple("2027-01-01T00:00:00.000Z", "UTC", "MediCabinet-2027-01-01.medicabinet"),
        Triple("2000-02-29T12:00:00.000Z", "UTC", "MediCabinet-2000-02-29.medicabinet")
    ).map { (instant, zone, expected) ->
        dynamicTest("$instant in $zone") {
            val date = assertNotNull(ArchiveDates.parse(instant))
            assertEquals(expected, CabinetTransfer.fileName(date, ZoneId.of(zone)))
        }
    }

    @Test
    fun aSingleDefaultLocationAndNoBoxNeedsNoBackup() {
        assertFalse(CabinetSummary(1, 0, 0, 0, 0).needsBackup)
        assertFalse(CabinetSummary(0, 0, 0, 0, 0).needsBackup)
        assertTrue(CabinetSummary(0, 1, 0, 0, 0).needsBackup)
        assertTrue(CabinetSummary(2, 0, 0, 0, 0).needsBackup)
        assertTrue(CabinetSummary(1, 1, 0, 0, 0).needsBackup)
        assertTrue(CabinetSummary(1, 0, 1, 0, 0).needsBackup)
        assertTrue(CabinetSummary(1, 0, 0, 1, 0).needsBackup)
    }

    @Test
    fun theSnapshotSummaryCountsEveryKindOfBox() {
        val cabinet = ArchiveDecoder.decode(SharedFixtures.goldenArchive()).cabinet
        assertEquals(CabinetSummary(3, 9, 2, 1, 2), cabinet.summary)
        assertTrue(cabinet.needsBackup)
    }
}
