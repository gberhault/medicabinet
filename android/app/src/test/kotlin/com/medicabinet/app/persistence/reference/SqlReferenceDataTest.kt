package com.medicabinet.app.persistence.reference

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.app.app.BootstrapState
import com.medicabinet.app.persistence.AppPaths
import com.medicabinet.app.persistence.Bootstrap
import com.medicabinet.core.CategoryID
import com.medicabinet.core.reference.ReferenceDataDate
import com.medicabinet.core.reference.ReferenceStatus
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.SharedFixtures
import java.io.File
import java.util.UUID
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reference data on Android: lookup against the shared fixtures, every way it can be unusable, the real bundled
 * assets and the timing budgets (requirements L3-L9).
 */
@RunWith(RobolectricTestRunner::class)
class SqlReferenceDataTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sample: ByteArray get() = SharedFixtures.referenceSample.readBytes()
    private val sampleManifest: String get() = SharedFixtures.text("reference-sample-manifest.json")
    private val installDirectory get() = File(folder.root, "reference")
    private val reported = mutableListOf<Pair<ReferenceUnavailableReason, String?>>()

    private fun source(assets: ReferenceAssets) = SqlReferenceData(
        ReferenceInstaller(assets, installDirectory),
        Dispatchers.Unconfined
    ) { reason, error -> reported += reason to error?.let { it::class.java.simpleName } }

    private fun sampleSource() = source(FakeReferenceAssets(sampleManifest, sample))

    private fun bundledSource(): SqlReferenceData {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SqlReferenceData(ReferenceInstaller(AndroidReferenceAssets(context.assets), installDirectory))
    }

    // --- shared lookup fixture ---

    @Test
    fun theSharedFixtureHasCases() {
        assertTrue(SharedFixtures.referenceLookup().size >= MIN_CASES)
    }

    @Test
    fun lookupMatchesTheSharedExpectationOfEveryCase() = runBlocking<Unit> {
        val source = sampleSource()
        for (case in SharedFixtures.referenceLookup()) {
            val product = source.product(case.gtin)
            val expected = case.expect.product
            assertEquals(expected?.name, product?.name, case.name)
            assertEquals(expected?.category, product?.category?.rawValue, case.name)
            assertEquals(expected?.cis, product?.cis, case.name)
            assertEquals(expected?.isWithdrawn, product?.isWithdrawn, case.name)
        }
    }

    @Test
    fun statusCarriesTheDataDateOfTheSample() = runBlocking<Unit> {
        assertEquals(ReferenceStatus.Available(ReferenceDataDate(2026, 11, 18)), sampleSource().status())
    }

    @Test
    fun aWithdrawnProductIsStillFound() = runBlocking<Unit> {
        assertEquals("TESTRETIRE 100 mg, comprimé", sampleSource().product("03400900000105")?.name)
    }

    // --- lazy, once ---

    @Test
    fun nothingIsInstalledOrOpenedUntilFirstUseAndOnlyOnce() = runBlocking<Unit> {
        val assets = FakeReferenceAssets(sampleManifest, sample)
        val source = source(assets)
        assertEquals(0, assets.databaseOpens)
        source.product("03400900000013")
        source.status()
        source.product("03400900000136")
        assertEquals(1, assets.databaseOpens)
    }

    // --- unavailable (L7) ---

    private fun unavailable(source: SqlReferenceData, reason: ReferenceUnavailableReason) = runBlocking<Unit> {
        assertEquals(ReferenceStatus.Unavailable(reason), source.status())
        assertNull(source.product("03400900000013"))
        assertEquals(listOf(reason), reported.map { it.first }, "one log entry carrying only the reason")
    }

    @Test
    fun missingAssetsMeanMissing() {
        unavailable(source(FakeReferenceAssets(null, null)), ReferenceUnavailableReason.MISSING)
    }

    @Test
    fun anAssetThatIsNotADatabaseIsUnreadable() {
        val garbage = "this is not a database, it is long enough to look like a header".toByteArray()
        unavailable(source(FakeReferenceAssets(sampleManifest, garbage)), ReferenceUnavailableReason.UNREADABLE)
    }

    @Test
    fun aTruncatedAssetIsUnreadable() {
        val truncated = sample.copyOf(sample.size / 2)
        unavailable(source(FakeReferenceAssets(sampleManifest, truncated)), ReferenceUnavailableReason.UNREADABLE)
    }

    @Test
    fun anotherSchemaVersionIsUnsupported() {
        val file = folder.newFile("v3.sqlite").also { it.writeBytes(sample) }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("PRAGMA user_version = 3")
        }
        val assets = FakeReferenceAssets(sampleManifest, file.readBytes())
        unavailable(source(assets), ReferenceUnavailableReason.UNSUPPORTED_SCHEMA)
    }

    @Test
    fun aCorruptedInstalledCopyIsReplacedFromTheAssetsOnce() = runBlocking<Unit> {
        val assets = FakeReferenceAssets(sampleManifest, sample)
        sampleSource().product("03400900000013")
        File(installDirectory, "reference.sqlite").writeBytes(byteArrayOf(1, 2, 3))

        val repaired = source(assets)
        assertEquals("TESTAMOL 500 mg, comprimé pelliculé", repaired.product("03400900000013")?.name)
        assertEquals(emptyList(), reported, "a repaired copy is not a failure")
    }

    @Test
    fun aDatabaseThatFailsAfterOpeningMakesProductsUnknownWithoutThrowing() = runBlocking<Unit> {
        sampleSource().status()
        val installed = File(installDirectory, "reference.sqlite")
        SQLiteDatabase.openDatabase(installed.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE presentation RENAME TO presentation_old")
        }
        val source = source(FakeReferenceAssets(sampleManifest, sample))
        assertIs<ReferenceStatus.Available>(source.status())
        assertNull(source.product("03400900000013"))
        assertEquals(listOf(ReferenceUnavailableReason.UNREADABLE), reported.map { it.first })
    }

    @Test
    fun theLogCarriesTheReasonAndExceptionClassNeverTheMessage() {
        val garbage = "TESTAMOL 3400900000013 secret".toByteArray()
        unavailable(source(FakeReferenceAssets(sampleManifest, garbage)), ReferenceUnavailableReason.UNREADABLE)
        assertEquals("ReferenceUnavailableException", reported.single().second)
    }

    @Test
    fun theCabinetStartsWhenTheReferenceDataIsMissing() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reference = source(FakeReferenceAssets(null, null))
        val bootstrap = Bootstrap(
            context,
            defaultLocationName = "Armoire",
            databaseName = "reference-bootstrap-${UUID.randomUUID()}.db",
            reference = reference
        )
        val state = bootstrap.run()
        val environment = assertIs<BootstrapState.Ready>(state).environment
        assertEquals(ReferenceStatus.Unavailable(ReferenceUnavailableReason.MISSING), environment.reference.status())
    }

    // --- the installed copy lives where Auto Backup does not look ---

    @Test
    fun theInstalledCopyIsUnderNoBackupStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = AppPaths(context).referenceDirectory
        assertTrue(directory.path.startsWith(context.noBackupFilesDir.path))
        assertFalse(directory.path.startsWith(context.getDatabasePath("x").parent.orEmpty()))
    }

    // --- the real bundled assets (L9) ---

    @Test
    fun theBundledDatabaseKnowsDoliprane() = runBlocking<Unit> {
        val source = bundledSource()
        val product = assertNotNull(source.product("03400935955838"))
        assertTrue(product.name.startsWith("DOLIPRANE 1000 mg"), product.name)
        assertEquals(CategoryID.PAIN_FEVER, product.category)
        assertEquals("60234100", product.cis) // #56: the BDPM page of this medicine is built from it
        assertFalse(product.isWithdrawn)
        assertIs<ReferenceStatus.Available>(source.status())
    }

    @Test
    fun theBundledDatabaseAnswersUnknownForAParapharmacyCode() = runBlocking<Unit> {
        assertNull(bundledSource().product("03401900000003"))
    }

    // --- timing (L6): generous budgets, several runs ---

    @Test
    fun theFirstInstallAndOpenStayUnderTheirBudget() = runBlocking<Unit> {
        val durations = (1..RUNS).map {
            File(folder.root, "run-$it").deleteRecursively()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val installer = ReferenceInstaller(AndroidReferenceAssets(context.assets), File(folder.root, "run-$it"))
            val source = SqlReferenceData(installer, Dispatchers.Unconfined)
            measureNanoTime { source.status() } / NANOS_PER_MILLI
        }
        assertTrue(durations.max() < FIRST_USE_BUDGET_MILLIS, "slowest first use took ${durations.max()} ms")
    }

    @Test
    fun lookupsOnTheFullDatabaseTakeUnder50Milliseconds() = runBlocking<Unit> {
        val source = bundledSource()
        source.status()
        val cip13s = firstCip13s(LOOKUPS)
        assertEquals(LOOKUPS, cip13s.size)
        val timings = cip13s.map { cip13 ->
            measureNanoTime { assertNotNull(source.product("0$cip13")) } / NANOS_PER_MILLI.toDouble()
        }.sorted()
        val p99 = timings[timings.size * P99 / PERCENT]
        assertTrue(p99 < LOOKUP_BUDGET_MILLIS, "p99 was $p99 ms")
        assertTrue(timings[timings.size / 2] < MEDIAN_BUDGET_MILLIS, "median was ${timings[timings.size / 2]} ms")
    }

    private fun firstCip13s(count: Int): List<String> {
        val installed = File(installDirectory, "reference.sqlite")
        return SQLiteDatabase.openDatabase(installed.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT cip13 FROM presentation ORDER BY cip13 LIMIT $count", null).use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
            }
        }
    }

    private companion object {
        const val MIN_CASES = 16
        const val RUNS = 3
        const val NANOS_PER_MILLI = 1_000_000L
        const val FIRST_USE_BUDGET_MILLIS = 3000L
        const val LOOKUPS = 500
        const val P99 = 99
        const val PERCENT = 100
        const val LOOKUP_BUDGET_MILLIS = 50.0
        const val MEDIAN_BUDGET_MILLIS = 5.0
    }
}
