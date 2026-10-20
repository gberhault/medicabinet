package com.medicabinet.core.archive

import com.medicabinet.core.Box
import com.medicabinet.core.FixedClock
import com.medicabinet.core.Location
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.ProductRef
import com.medicabinet.core.testing.ArchiveFixtureFiles
import com.medicabinet.core.testing.InMemoryArchiveBackupStore
import com.medicabinet.core.testing.InMemoryArchiveStore
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Importing, backing up and exporting with the products the household named (archive `formatVersion` 2). */
class CabinetTransferNamedProductsTest {
    private val app = ArchiveApp("MediCabinet", "ios", "0.7.0", "10")
    private val exportedAt = ArchiveDates.instant(1_792_570_530_123L)

    private class Setup(
        val store: InMemoryArchiveStore,
        val backups: InMemoryArchiveBackupStore,
        val transfer: CabinetTransfer,
        val golden: ByteArray,
        val goldenCabinet: CabinetContents
    )

    private fun setup(initial: CabinetContents? = null): Setup {
        val golden = ArchiveFixtureFiles.goldenArchive()
        val goldenCabinet = ArchiveDecoder.decode(golden).cabinet
        val store = InMemoryArchiveStore(initial ?: CabinetContents(emptyList(), emptyList()))
        val backups = InMemoryArchiveBackupStore()
        val transfer = CabinetTransfer(store, backups, FixedClock(exportedAt), app)
        return Setup(store, backups, transfer, golden, goldenCabinet)
    }

    private fun smallCabinet(name: String): CabinetContents {
        val location = Location(name = name, isDefault = true, createdAt = exportedAt)
        val box = Box(product = ProductRef(name = "Only box"), locationId = location.id, createdAt = exportedAt)
        return CabinetContents(listOf(ArchivedLocation(location)), listOf(ArchivedBox(box)))
    }

    /** Created at different instants: the archive lists products by creation time, so a round trip keeps the order. */
    private fun smallCabinetWithProducts(name: String): CabinetContents {
        val live = NamedProduct(gtin = "03401234567890", name = "Crème", createdAt = exportedAt)
        val forgotten = NamedProduct(gtin = "03401234567891", name = "Forgotten", createdAt = exportedAt.plusSeconds(1))
        return smallCabinet(name).copy(
            namedProducts = listOf(ArchivedNamedProduct(live), ArchivedNamedProduct(forgotten, deletedAt = exportedAt))
        )
    }

    @Test
    fun aVersionOneFileLeavesTheNamedProductsToBeFilledAfterTheImport() = runBlocking {
        val context = setup(smallCabinetWithProducts("Old"))
        val preview = context.transfer.preview(ArchiveFixtureFiles.goldenArchive(1))
        assertTrue(preview.archive.cabinet.namedProductsNeedFill)
        assertEquals(0, preview.file.namedProducts)

        context.transfer.apply(preview, BackupReason.BEFORE_IMPORT)

        assertTrue(context.store.backfillPending())
        val snapshot = context.store.snapshot()
        assertTrue(snapshot.namedProducts.isEmpty())
        assertFalse(snapshot.namedProductsNeedFill)
    }

    @Test
    fun aVersionTwoFileKeepsItsOwnNamedProductsAndNeedsNoFill() = runBlocking {
        val context = setup()
        context.store.replace(smallCabinetWithProducts("Old").copy(namedProductsNeedFill = true)) { }
        assertTrue(context.store.backfillPending())

        val preview = context.transfer.preview(context.golden)
        assertFalse(preview.archive.cabinet.namedProductsNeedFill)
        assertEquals(2, preview.file.namedProducts)
        context.transfer.apply(preview, BackupReason.BEFORE_IMPORT)

        assertFalse(context.store.backfillPending())
        assertEquals(context.goldenCabinet, context.store.snapshot())
        assertEquals(3, context.store.snapshot().namedProducts.size)
    }

    @Test
    fun theAutomaticBackupCarriesTheNamedProductsForgottenOnesIncluded() = runBlocking {
        val old = smallCabinetWithProducts("Old")
        val context = setup(old)
        val result = context.transfer.apply(context.transfer.preview(context.golden), BackupReason.BEFORE_IMPORT)
        val backup = assertNotNull(result.backup)
        val saved = ArchiveDecoder.decode(context.backups.read(backup.id))
        assertEquals(2, saved.sourceFormatVersion)
        assertEquals(old, saved.cabinet)
        assertEquals(2, saved.cabinet.namedProducts.size)

        // Restoring it brings the products back and asks for no fill.
        val restored = context.transfer.apply(
            context.transfer.preview(context.transfer.readBackup(backup.id)),
            BackupReason.BEFORE_RESTORE
        )
        assertEquals(1, restored.imported.namedProducts)
        assertEquals(old, context.store.snapshot())
        assertFalse(context.store.backfillPending())
    }

    @Test
    fun aCabinetHoldingOnlyAForgottenProductIsStillBackedUp() = runBlocking {
        val home = ArchivedLocation(Location(name = "Home", isDefault = true, createdAt = exportedAt))
        val forgotten = NamedProduct(gtin = "03401234567890", name = "Forgotten", createdAt = exportedAt)
        val products = listOf(ArchivedNamedProduct(forgotten, exportedAt))
        val onlyForgotten = CabinetContents(listOf(home), emptyList(), products)
        val context = setup(onlyForgotten)

        val result = context.transfer.apply(context.transfer.preview(context.golden), BackupReason.BEFORE_IMPORT)

        val backup = assertNotNull(result.backup)
        assertEquals(onlyForgotten, ArchiveDecoder.decode(context.backups.read(backup.id)).cabinet)
    }

    @Test
    fun anExportThenAnImportOnAnotherPhoneKeepsEveryNamedProduct() = runBlocking {
        val original = smallCabinetWithProducts("Home")
        val exported = setup(original).transfer.exportArchive()
        val second = setup()

        second.transfer.apply(second.transfer.preview(exported), BackupReason.BEFORE_IMPORT)

        assertEquals(original, second.store.snapshot())
        assertContentEquals(exported, second.transfer.exportArchive())
    }
}
