package com.medicabinet.app.persistence.reference

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.core.reference.ReferenceUnavailableReason
import com.medicabinet.core.testing.SharedFixtures
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Assets backed by plain bytes, counting how often the database is opened; can fail part-way through a copy. */
class FakeReferenceAssets(
    private val manifestText: String?,
    private val databaseBytes: ByteArray?,
    private val failAfterBytes: Int? = null
) : ReferenceAssets {
    var databaseOpens = 0
        private set

    override fun manifest(): String? = manifestText

    override fun database(): InputStream? {
        databaseOpens++
        return databaseBytes?.let { bytes -> failAfterBytes?.let { interrupted(bytes, it) } ?: bytes.inputStream() }
    }

    private fun interrupted(bytes: ByteArray, limit: Int) = object : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= limit) throw IOException("simulated interruption")
            return bytes[position++].toInt() and BYTE_MASK
        }
    }

    private companion object {
        const val BYTE_MASK = 0xff
    }
}

@RunWith(RobolectricTestRunner::class)
class ReferenceInstallerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sample: ByteArray get() = SharedFixtures.referenceSample.readBytes()
    private val sampleManifest: String get() = SharedFixtures.text("reference-sample-manifest.json")
    private val installDirectory get() = File(folder.root, "reference")

    private fun installer(assets: ReferenceAssets) = ReferenceInstaller(assets, installDirectory)

    private fun failureOf(assets: ReferenceAssets): ReferenceUnavailableReason =
        assertFailsWith<ReferenceUnavailableException> { installer(assets).install() }.reason

    private fun manifestWith(hash: String) = """{"formatVersion": 1, "contentSha256": "$hash"}"""

    private fun installedFiles() = installDirectory.list().orEmpty().toSet()

    /** A second, valid database with another content (and so another hash), as a newer app would bundle. */
    private fun changedDatabase(): Pair<ByteArray, String> {
        val file = folder.newFile("changed.sqlite").also { it.writeBytes(sample) }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE presentation SET name = 'RENAMED BY A NEWER APP' WHERE cip13 = '3400900000013'")
        }
        val hash = ReferenceDatabase.open(file).use { it.contentSha256() }
        return file.readBytes() to hash
    }

    @Test
    fun theSampleManifestHashMatchesTheOneThePythonBuildComputed() {
        val expected = Regex("\"contentSha256\": \"([0-9a-f]{64})\"").find(sampleManifest)?.groupValues?.get(1)
        val file = folder.newFile("sample.sqlite").also { it.writeBytes(sample) }
        assertEquals(expected, ReferenceDatabase.open(file).use { it.contentSha256() })
    }

    @Test
    fun firstInstallCopiesVerifiesAndLeavesNoTemporaryFile() {
        val installed = installer(FakeReferenceAssets(sampleManifest, sample)).install()
        assertTrue(installed.isFile)
        assertEquals(setOf("reference.sqlite", "installed.sha256"), installedFiles())
        ReferenceDatabase.open(installed).use { assertEquals(2026, it.dataDate.year) }
    }

    @Test
    fun anUnchangedBundledVersionIsNotCopiedAgain() {
        val assets = FakeReferenceAssets(sampleManifest, sample)
        val installer = installer(assets)
        installer.install()
        installer.install()
        assertEquals(1, assets.databaseOpens)
    }

    @Test
    fun aChangedBundledVersionReplacesTheInstalledCopy() {
        installer(FakeReferenceAssets(sampleManifest, sample)).install()
        val (bytes, hash) = changedDatabase()
        val installed = installer(FakeReferenceAssets(manifestWith(hash), bytes)).install()
        ReferenceDatabase.open(installed).use {
            assertEquals("RENAMED BY A NEWER APP", it.product("3400900000013")?.name)
        }
    }

    @Test
    fun anInterruptedCopyLeavesNothingAndTheNextCallRetries() {
        val interrupted = FakeReferenceAssets(sampleManifest, sample, failAfterBytes = sample.size / 2)
        assertEquals(ReferenceUnavailableReason.UNREADABLE, failureOf(interrupted))
        assertEquals(emptySet(), installedFiles())

        assertTrue(installer(FakeReferenceAssets(sampleManifest, sample)).install().isFile)
    }

    @Test
    fun aCopyThatDoesNotMatchTheManifestHashIsRefusedAndRemoved() {
        val assets = FakeReferenceAssets(manifestWith("0".repeat(HASH_LENGTH)), sample)
        assertEquals(ReferenceUnavailableReason.UNREADABLE, failureOf(assets))
        assertEquals(emptySet(), installedFiles())
    }

    @Test
    fun aFailedReplacementKeepsTheWorkingInstalledCopy() {
        val installed = installer(FakeReferenceAssets(sampleManifest, sample)).install()
        val before = installed.readBytes()
        val (bytes, _) = changedDatabase()
        val wrongManifest = FakeReferenceAssets(manifestWith("1".repeat(HASH_LENGTH)), bytes)
        assertEquals(ReferenceUnavailableReason.UNREADABLE, failureOf(wrongManifest))
        assertTrue(before.contentEquals(installed.readBytes()))
    }

    @Test
    fun missingAssetsMeanMissing() {
        assertEquals(ReferenceUnavailableReason.MISSING, failureOf(FakeReferenceAssets(null, sample)))
        assertEquals(ReferenceUnavailableReason.MISSING, failureOf(FakeReferenceAssets(sampleManifest, null)))
    }

    @Test
    fun aMalformedManifestIsUnreadable() {
        for (text in listOf("not json", "{}", """{"contentSha256": 3}""")) {
            assertEquals(ReferenceUnavailableReason.UNREADABLE, failureOf(FakeReferenceAssets(text, sample)), text)
        }
    }

    @Test
    fun anAssetThatIsNotADatabaseIsUnreadable() {
        val garbage = "this is not a database, it is long enough to look like a header".toByteArray()
        val assets = FakeReferenceAssets(manifestWith("a".repeat(HASH_LENGTH)), garbage)
        assertEquals(ReferenceUnavailableReason.UNREADABLE, failureOf(assets))
        assertFalse(File(installDirectory, "reference.sqlite").exists())
    }

    @Test
    fun anAssetOfAnotherSchemaVersionIsRefusedAsUnsupported() {
        val file = folder.newFile("v2.sqlite").also { it.writeBytes(sample) }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("PRAGMA user_version = 3")
        }
        val assets = FakeReferenceAssets(manifestWith("b".repeat(HASH_LENGTH)), file.readBytes())
        assertEquals(ReferenceUnavailableReason.UNSUPPORTED_SCHEMA, failureOf(assets))
    }

    @Test
    fun forceCopiesAgainAndRepairsACorruptedInstalledFile() {
        val assets = FakeReferenceAssets(sampleManifest, sample)
        val installer = installer(assets)
        installer.install().writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(3, installer.install().length(), "without force the marker is trusted")
        assertTrue(sample.contentEquals(installer.install(force = true).readBytes()))
        assertEquals(2, assets.databaseOpens)
    }

    @Test
    fun theBundledAssetsInstallAndMatchTheManifestOfThePythonBuild() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val installed = installer(AndroidReferenceAssets(context.assets)).install()
        ReferenceDatabase.open(installed).use { assertTrue(it.dataDate.year >= 2026) }
    }

    private companion object {
        const val HASH_LENGTH = 64
    }
}
