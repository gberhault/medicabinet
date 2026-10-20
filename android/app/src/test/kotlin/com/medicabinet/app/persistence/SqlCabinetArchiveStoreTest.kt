package com.medicabinet.app.persistence

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.core.Box
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.archive.ArchiveApp
import com.medicabinet.core.archive.ArchiveDecoder
import com.medicabinet.core.archive.ArchivedBox
import com.medicabinet.core.archive.ArchivedNamedProduct
import com.medicabinet.core.archive.BackupReason
import com.medicabinet.core.archive.CabinetArchiveStore
import com.medicabinet.core.archive.CabinetContents
import com.medicabinet.core.archive.CabinetTransfer
import com.medicabinet.core.testing.ArchiveFixtureFiles
import com.medicabinet.core.testing.ArchiveStoreContract
import com.medicabinet.core.testing.ArchiveStoreUnderContract
import com.medicabinet.core.testing.MutableClock
import com.medicabinet.core.testing.SyntheticArchive
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RobolectricTestRunner

private fun newCabinet() =
    CabinetDatabase(ApplicationProvider.getApplicationContext(), name = "archive-${UUID.randomUUID()}.db")

/** Stores return rows in their own order; comparisons are on contents. */
private fun normalized(contents: CabinetContents) = CabinetContents(
    contents.locations.sortedBy { it.location.id.toString() },
    contents.boxes.sortedBy { it.box.id.toString() },
    contents.namedProducts.sortedBy { it.product.id.toString() }
)

/** The contract reads the backfill marker through the real named-product repository. */
private class ContractedSqlStore(cabinet: CabinetDatabase, private val store: SqlCabinetArchiveStore) :
    ArchiveStoreUnderContract,
    CabinetArchiveStore by store {
    private val named = SqlNamedProductRepository(cabinet, MutableClock(Instant.ofEpochSecond(1_800_000_000)))

    override suspend fun backfillPending(): Boolean = named.backfillPending()
}

/** The shared store contract (`fixtures/archive-store-contract.json`) on plain SQLite. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class SqlCabinetArchiveStoreContractTest(private val check: ArchiveStoreContract.Check) {
    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun checks(): List<Array<Any>> = ArchiveStoreContract.checks.map { arrayOf(it) }
    }

    @Test
    fun sqlStoreSatisfiesTheContract() = runBlocking {
        newCabinet().use { cabinet -> check.run { ContractedSqlStore(cabinet, SqlCabinetArchiveStore(cabinet)) } }
    }

    @Test
    fun contractChecksMatchTheSharedList() {
        assertEquals(ArchiveFixtureFiles.archiveStoreContractCheckNames(), ArchiveStoreContract.checks.map { it.name })
    }
}

/** What the fake has no equivalent of: SQL rows, derived columns, rollback, the export → import round trip. */
@RunWith(RobolectricTestRunner::class)
class SqlCabinetArchiveStoreTest {
    private val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
    private val app = ArchiveApp("MediCabinet", "android", "0.5.0", "6")
    private val opened = mutableListOf<CabinetDatabase>()
    private val folders = mutableListOf<File>()
    private val golden = ArchiveDecoder.decode(ArchiveFixtureFiles.goldenArchive()).cabinet

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        folders.forEach { it.deleteRecursively() }
    }

    private fun cabinet() = newCabinet().also { opened += it }

    private fun transfer(cabinet: CabinetDatabase): CabinetTransfer {
        val folder = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "b-${UUID.randomUUID()}"
        )
        folders += folder
        return CabinetTransfer(SqlCabinetArchiveStore(cabinet), FileArchiveBackupStore(folder), clock, app)
    }

    @Test
    fun everyFieldVariantOfTheGoldenFileIsStoredAndReadBackUnchanged() = runBlocking {
        val store = SqlCabinetArchiveStore(cabinet())
        store.replace(golden) { }
        assertEquals(normalized(golden), normalized(store.snapshot()))
    }

    @Test
    fun theExpirySortKeyIsRecomputedAndAnUnknownExpiryStaysNull() = runBlocking {
        val cabinet = cabinet()
        SqlCabinetArchiveStore(cabinet).replace(golden) { }
        val rows = SqlProbe.query(
            cabinet.database,
            "SELECT id, expiryYear, expiryMonth, expiryDay, expirySortKey FROM box"
        ) {
            it.getString(0) to it.getString(4)
        }.toMap()
        for (archived in golden.boxes) {
            assertEquals(archived.box.expiry?.sortKey, rows[archived.box.id.toString().uppercase()])
        }
        assertTrue(golden.boxes.any { it.box.expiry == null } && golden.boxes.any { it.box.expiry?.day == null })
    }

    @Test
    fun aCabinetExportedThenImportedOnANewPhoneIsEqualAndExportsTheSameBytes() = runBlocking {
        val first = cabinet()
        SqlCabinetArchiveStore(first).replace(golden) { }
        val bytes = transfer(first).exportArchive()

        // A new phone: only the default location the app creates at launch, so nothing to back up.
        val second = cabinet()
        SqlLocationRepository(second, clock).ensureDefault("Armoire")
        val target = transfer(second)
        val preview = target.preview(bytes)
        assertFalse(preview.willBackUp)
        val result = target.apply(preview, BackupReason.BEFORE_IMPORT)

        assertEquals(null, result.backup)
        assertEquals(
            normalized(SqlCabinetArchiveStore(first).snapshot()),
            normalized(SqlCabinetArchiveStore(second).snapshot())
        )
        assertContentEquals(bytes, target.exportArchive())
    }

    @Test
    fun importingOverACabinetKeepsABackupOfExactlyTheReplacedCabinet() = runBlocking {
        val cabinet = cabinet()
        val store = SqlCabinetArchiveStore(cabinet)
        store.replace(golden) { }
        val transfer = transfer(cabinet)
        val incoming = SyntheticArchive.json(locations = 2, boxes = 3).toByteArray()

        val result = transfer.apply(transfer.preview(incoming), BackupReason.BEFORE_IMPORT)

        val backup = assertNotNull(result.backup)
        assertEquals(golden.summary, backup.summary)
        val kept = ArchiveDecoder.decode(transfer.readBackup(backup.id)).cabinet
        assertEquals(normalized(golden), normalized(kept))
        assertEquals(normalized(ArchiveDecoder.decode(incoming).cabinet), normalized(store.snapshot()))
    }

    @Test
    fun anInsertFailingMidwayRollsEverythingBack() = runBlocking {
        val cabinet = cabinet()
        val store = SqlCabinetArchiveStore(cabinet)
        store.replace(golden) { }
        val before = cabinet.changes.value
        val location = golden.locations.first { it.location.isDefault }
        val tooLong = Box(
            product = ProductRef(name = "x".repeat(201)),
            locationId = location.location.id,
            createdAt = clock.now()
        )
        val bad = golden.copy(boxes = golden.boxes + ArchivedBox(tooLong))
        var hookCalled = false

        assertFailsWith<SQLiteConstraintException> { store.replace(bad) { hookCalled = true } }

        assertTrue(hookCalled)
        assertEquals(normalized(golden), normalized(store.snapshot()))
        assertEquals(before, cabinet.changes.value, "a rolled-back replace announces no change")
    }

    @Test
    fun twoLiveBoxesWithTheSameSerialAreRefusedBeforeTheHook() = runBlocking {
        val store = SqlCabinetArchiveStore(cabinet())
        val live = golden.boxes.first { it.deletedAt == null && it.box.serial != null && it.box.product.gtin != null }
        val twin = live.copy(box = live.box.copy(id = UUID.randomUUID()))
        var hookCalled = false
        val error = assertFailsWith<RepositoryException> {
            store.replace(CabinetContents(golden.locations, golden.boxes + twin)) { hookCalled = true }
        }
        assertEquals(RepositoryError.DUPLICATE_SERIAL, error.error)
        assertFalse(hookCalled)
    }

    // region Named products

    private fun namedProducts(cabinet: CabinetDatabase) = SqlNamedProductRepository(cabinet, clock)

    /**
     * The golden file carries a live and a forgotten product with one GTIN, and a live one with a user category: all of
     * them reach the table, and the repository sees only the live ones.
     */
    @Test
    fun namedProductsOfTheGoldenFileLandInTheTableAndTheRepositoryShowsTheLiveOnes() = runBlocking {
        val cabinet = cabinet()
        val named = namedProducts(cabinet)
        SqlCabinetArchiveStore(cabinet).replace(golden) { }

        val rows = SqlProbe.query(cabinet.database, "SELECT COUNT(*) FROM namedProduct") { it.getInt(0) }
        assertEquals(listOf(3), rows)
        val shown = named.namedProduct("03400936404571")
        assertEquals("Produit 2", shown?.name)
        val chosen = named.namedProduct("03401234567890")
        assertEquals("skin-wound", chosen?.category?.rawValue)
        assertTrue(chosen?.categoryOverridden == true)
        assertFalse(named.backfillPending())
    }

    /**
     * A version 1 file has no named products: the import leaves the table empty and the fill pending, and the snapshot
     * still reports no fill (the marker is the store's, not the cabinet's).
     */
    @Test
    fun aVersionOneImportSetsTheMarkerAndClearsTheTable() = runBlocking {
        val old = ArchiveDecoder.decode(ArchiveFixtureFiles.goldenArchive(1)).cabinet
        assertTrue(old.namedProductsNeedFill)
        val cabinet = cabinet()
        val named = namedProducts(cabinet)
        val store = SqlCabinetArchiveStore(cabinet)
        store.replace(golden) { }
        assertFalse(named.backfillPending())

        store.replace(old) { }

        val snapshot = store.snapshot()
        assertTrue(snapshot.namedProducts.isEmpty())
        assertFalse(snapshot.namedProductsNeedFill)
        assertTrue(named.backfillPending())
    }

    /** The same GTIN live twice is refused before anything is touched, with the table and the marker untouched. */
    @Test
    fun twoLiveProductsWithOneGtinAreARepositoryErrorAndChangeNothing() = runBlocking {
        val cabinet = cabinet()
        val store = SqlCabinetArchiveStore(cabinet)
        store.replace(golden) { }
        val before = store.snapshot()
        val twins = (1..2).map {
            ArchivedNamedProduct(NamedProduct(gtin = "03401234567890", name = "Twin $it", createdAt = clock.now()))
        }
        var hookCalled = false

        val error = assertFailsWith<RepositoryException> {
            store.replace(golden.copy(namedProducts = twins, namedProductsNeedFill = true)) { hookCalled = true }
        }

        assertEquals(RepositoryError.DUPLICATE, error.error)
        assertFalse(hookCalled)
        assertEquals(before, store.snapshot())
        assertFalse(namedProducts(cabinet).backfillPending())
    }

    /** A row the table refuses (the CHECK on the name length) fails the transaction and rolls the marker back too. */
    @Test
    fun aNamedProductInsertFailingMidwayRollsTheWholeReplaceBack() = runBlocking {
        val cabinet = cabinet()
        val store = SqlCabinetArchiveStore(cabinet)
        store.replace(golden) { }
        val before = store.snapshot()
        val first = golden.namedProducts.first()
        val tooLong = first.copy(product = first.product.copy(name = "n".repeat(201)))
        val products = listOf(tooLong) + golden.namedProducts.drop(1)
        val bad = golden.copy(namedProducts = products, namedProductsNeedFill = true)

        assertFailsWith<SQLiteConstraintException> { store.replace(bad) { } }

        assertEquals(before, store.snapshot())
        assertFalse(namedProducts(cabinet).backfillPending())
    }

    // endregion

    @Test
    fun aSuccessfulReplaceIsAnnouncedToTheObservers() = runBlocking {
        val cabinet = cabinet()
        val before = cabinet.changes.value
        SqlCabinetArchiveStore(cabinet).replace(golden) { }
        assertTrue(cabinet.changes.value > before)
    }

    // region Performance (design §9: best of 5, 1 000 boxes, ≤ 600 ms each)

    private fun best(runs: Int = 5, work: () -> Unit): Duration = (1..runs).map { measureTime(work) }.min()

    @Test
    fun exportingAThousandBoxCabinetStaysWithinBudget() = runBlocking {
        val cabinet = cabinet()
        val thousand = ArchiveDecoder.decode(SyntheticArchive.json(locations = 3, boxes = 1_000).toByteArray())
        SqlCabinetArchiveStore(cabinet).replace(thousand.cabinet) { }
        val transfer = transfer(cabinet)
        val time = best { runBlocking { transfer.exportArchive() } }
        println("exportArchive, 1 000 boxes, best of 5: $time")
        assertTrue(time < 600.milliseconds, "export took $time")
    }

    @Test
    fun importingAThousandBoxCabinetWithItsBackupStaysWithinBudget() = runBlocking {
        val cabinet = cabinet()
        val bytes = SyntheticArchive.json(locations = 3, boxes = 1_000).toByteArray()
        val transfer = transfer(cabinet)
        val preview = transfer.preview(bytes)
        transfer.apply(preview, BackupReason.BEFORE_IMPORT)
        // Each run backs up the 1 000 boxes already there, then replaces them.
        val time = best { runBlocking { transfer.apply(preview, BackupReason.BEFORE_IMPORT) } }
        println("apply with backup, 1 000 boxes, best of 5: $time")
        assertTrue(time < 600.milliseconds, "import took $time")
    }

    // endregion
}
