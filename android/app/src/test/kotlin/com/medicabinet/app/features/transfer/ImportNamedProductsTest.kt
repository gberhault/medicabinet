package com.medicabinet.app.features.transfer

import androidx.test.core.app.ApplicationProvider
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.app.NamedProductBackfillLauncher
import com.medicabinet.app.persistence.CabinetDatabase
import com.medicabinet.app.persistence.FileArchiveBackupStore
import com.medicabinet.app.persistence.SqlBoxRepository
import com.medicabinet.app.persistence.SqlCabinetArchiveStore
import com.medicabinet.app.persistence.SqlLocationRepository
import com.medicabinet.app.persistence.SqlNamedProductRepository
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ProductRef
import com.medicabinet.core.archive.ArchiveApp
import com.medicabinet.core.archive.ArchiveDecoder
import com.medicabinet.core.archive.ArchivedNamedProduct
import com.medicabinet.core.archive.BackupReason
import com.medicabinet.core.archive.CabinetTransfer
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.testing.ArchiveFixtureFiles
import com.medicabinet.core.testing.InMemoryReferenceData
import com.medicabinet.core.testing.MutableClock
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Archive version 2 through the whole app stack on SQLite (#57): the products the household named travel in the export,
 * in the automatic backup and in a restore, and a version 1 file (or an old backup) gets them filled from its boxes by
 * the real runner once the import is done.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImportNamedProductsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
    private val opened = mutableListOf<CabinetDatabase>()
    private val folders = mutableListOf<File>()

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun cleanUp() {
        Dispatchers.resetMain()
        opened.forEach { it.close() }
        folders.forEach { it.deleteRecursively() }
    }

    /** One phone: a SQLite cabinet, its backups folder and the environment the fill reads. */
    private inner class Phone {
        val cabinet = CabinetDatabase(ApplicationProvider.getApplicationContext(), name = "p-${UUID.randomUUID()}.db")
            .also { opened += it }
        val folder = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "b-${UUID.randomUUID()}"
        ).also { folders += it }
        val named = SqlNamedProductRepository(cabinet, clock)
        val boxes = SqlBoxRepository(cabinet, clock)
        val store = SqlCabinetArchiveStore(cabinet)
        val transfer = CabinetTransfer(
            store,
            FileArchiveBackupStore(folder),
            clock,
            ArchiveApp("MediCabinet", "android", "0.7.0", "9")
        )

        /** A reference that is available and names nothing: every code is one the household has to name. */
        val environment = AppEnvironment(
            SqlLocationRepository(cabinet, clock),
            boxes,
            named,
            clock,
            InMemoryReferenceData(status = ReferenceStatus.Available(ReferenceDataDate(2026, 10, 18))),
            ZoneOffset.UTC,
            transfer = transfer
        )
        val fills = mutableListOf<Job>()

        /** The app always has its default location (bootstrap makes it), and an archive needs one. */
        init {
            runBlocking { environment.locations.ensureDefault("Cabinet") }
        }

        private val launcher =
            NamedProductBackfillLauncher(CoroutineScope(SupervisorJob() + dispatcher), Dispatchers.IO)

        fun import(request: ImportRequest): ImportViewModel {
            val model = ImportViewModel(transfer, request, environment::cabinetReplaced, Dispatchers.IO) {
                fills += launcher.start(environment)
            }
            // The check runs on the IO dispatcher: wait for the preview.
            while (model.state.value.let { it !is ImportState.Preview && it !is ImportState.Failed }) Thread.sleep(5)
            model.replace()
            model.confirm()
            return model
        }

        fun finish(model: ImportViewModel): ImportState {
            while (model.state.value is ImportState.Applying) Thread.sleep(5)
            runBlocking { fills.toList().joinAll() }
            return model.state.value
        }

        suspend fun addBox(name: String, gtin: String = "03409999999999") {
            val location = environment.locations.ensureDefault("Cabinet")
            boxes.insert(Box(product = ProductRef(gtin, name), locationId = location.id, createdAt = clock.now()))
        }
    }

    private fun List<ArchivedNamedProduct>.byId() = sortedBy { it.product.id }

    private fun fileOf(bytes: ByteArray) = ImportRequest.File(Incoming.Ready(bytes))

    // MARK: Version 1

    @Test
    fun aVersionOneImportFillsTheNamedProductsFromTheImportedBoxes() = runBlocking {
        val phone = Phone()
        phone.named.remember("03409999999999", "Old product", CategoryID.UNCATEGORIZED, categoryOverridden = false)
        val version1 = ArchiveFixtureFiles.goldenArchive(1)

        val model = phone.import(fileOf(version1))
        assertIs<ImportState.Done>(phone.finish(model))

        assertEquals(1, phone.fills.size)
        assertFalse(phone.named.backfillPending())
        assertNull(phone.named.namedProduct("03409999999999"), "the old cabinet's products are gone")
        assertEquals("Produit 2", phone.named.namedProduct("03400936404571")?.name)
        // The newest live box with the other code names it, in the cabinet or not, never a deleted one.
        val newest = ArchiveDecoder.decode(version1).cabinet.boxes
            .filter { it.deletedAt == null && it.box.product.gtin == "03400936404564" }
            .maxBy { it.box.createdAt }
        assertEquals(newest.box.product.name, phone.named.namedProduct("03400936404564")?.name)
        val stored = phone.store.snapshot().namedProducts
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.deletedAt == null })
    }

    @Test
    fun aBackupFromBeforeNamedProductsExistedAlsoGetsItsProductsFilled() = runBlocking {
        val phone = Phone()
        // A version 1 backup file, as an earlier app version wrote it.
        phone.folder.mkdirs()
        val name = "pre-import-20261019-081530-123.medicabinet"
        File(phone.folder, name).writeBytes(ArchiveFixtureFiles.goldenArchive(1))

        val model = phone.import(ImportRequest.Backup(name, clock.now()))

        assertIs<ImportState.Done>(phone.finish(model))
        assertEquals("Produit 2", phone.named.namedProduct("03400936404571")?.name)
        assertFalse(phone.named.backfillPending())
    }

    // MARK: Version 2

    @Test
    fun aVersionTwoImportKeepsTheFilesNamedProductsAndTheFillHasNothingToDo() = runBlocking {
        val phone = Phone()
        val golden = ArchiveFixtureFiles.goldenArchive()
        val expected = ArchiveDecoder.decode(golden).cabinet

        val model = phone.import(fileOf(golden))
        val done = assertIs<ImportState.Done>(phone.finish(model))

        assertEquals(2, done.result.imported.namedProducts)
        assertFalse(phone.named.backfillPending())
        // The boxes' codes are unknown to this reference, yet no product was invented: only the file's three exist.
        assertEquals(expected.namedProducts.byId(), phone.store.snapshot().namedProducts.byId())
        assertEquals(3, phone.store.snapshot().namedProducts.size)
        val chosen = assertNotNull(phone.named.namedProduct("03401234567890"))
        assertEquals("Crème solaire SPF 50", chosen.name)
        assertTrue(chosen.categoryOverridden)
    }

    // MARK: Backup, restore, export

    @Test
    fun theAutomaticBackupCarriesTheNamedProductsAndARestoreBringsThemBack() = runBlocking {
        val phone = Phone()
        phone.addBox("Doliprane")
        phone.named.remember("03401111111111", "Crème", CategoryID.UNCATEGORIZED, categoryOverridden = false)
        phone.named.remember("03402222222222", "Test", CategoryID.UNCATEGORIZED, categoryOverridden = false)
        phone.named.forget(assertNotNull(phone.named.namedProduct("03402222222222")).id)
        val before = phone.store.snapshot()
        assertEquals(2, before.namedProducts.size)

        val importing = phone.import(fileOf(ArchiveFixtureFiles.goldenArchive()))
        val entry = assertNotNull(assertIs<ImportState.Done>(phone.finish(importing)).result.backup)
        val saved = ArchiveDecoder.decode(phone.transfer.readBackup(entry.id))
        assertEquals(2, saved.cabinet.namedProducts.size)
        assertTrue(saved.cabinet.namedProducts.any { it.deletedAt != null }, "the forgotten product is in the backup")

        val restoring = phone.import(ImportRequest.Backup(entry.id, entry.takenAt))
        assertIs<ImportState.Done>(phone.finish(restoring))

        assertEquals(before.namedProducts.byId(), phone.store.snapshot().namedProducts.byId())
        assertEquals("Crème", phone.named.namedProduct("03401111111111")?.name)
        assertNull(phone.named.namedProduct("03402222222222"), "a forgotten product stays forgotten")
    }

    @Test
    fun theFillNeverStartsWhenTheBackupFails() = runBlocking {
        val phone = Phone()
        phone.addBox("Doliprane")
        // A file where the backups folder should be: the backup cannot be written and nothing is replaced.
        phone.folder.parentFile?.mkdirs()
        phone.folder.writeText("not a folder")

        val model = phone.import(fileOf(ArchiveFixtureFiles.goldenArchive()))

        assertEquals(ImportState.Failed(ImportFailure.BACKUP_FAILED), phone.finish(model))
        assertTrue(phone.fills.isEmpty())
    }

    @Test
    fun anExportThenAnImportOnAnotherPhoneKeepsEveryNamedProductForgottenOnesIncluded() = runBlocking {
        val first = Phone()
        val second = Phone()
        first.addBox("Doliprane")
        first.named.remember("03401111111111", "Crème", CategoryID("skin-wound"), categoryOverridden = true)
        first.named.remember("03402222222222", "Test", CategoryID.UNCATEGORIZED, categoryOverridden = false)
        first.named.forget(assertNotNull(first.named.namedProduct("03402222222222")).id)
        val exported = first.transfer.exportArchive()
        second.addBox("Spare")

        val done = assertIs<ImportState.Done>(second.finish(second.import(fileOf(exported))))

        assertEquals(1, done.result.imported.namedProducts)
        assertEquals(first.store.snapshot().namedProducts.byId(), second.store.snapshot().namedProducts.byId())
        assertEquals(first.store.snapshot().boxes, second.store.snapshot().boxes)
        assertTrue(exported.contentEquals(second.transfer.exportArchive()))
        assertEquals(CategoryID("skin-wound"), second.named.namedProduct("03401111111111")?.category)
        assertEquals(BackupReason.BEFORE_IMPORT, done.result.backup?.reason)
    }
}
