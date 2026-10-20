package com.medicabinet.core.archive

import com.medicabinet.core.testing.ArchiveCase
import com.medicabinet.core.testing.ArchiveFixtureFiles
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Runs every case of the repository-wide `fixtures/archive-cases.json`, which the Swift core also runs, and
 * the canonical byte-for-byte check on the golden archive. A case added there runs here with no code change.
 */
class ArchiveFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = ArchiveFixtureFiles.archiveCaseFixture()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 250, "expected at least 250 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> =
        ArchiveFixtureFiles.archiveCaseFixture().cases.map { case -> dynamicTest(case.name) { check(case) } }

    private fun check(case: ArchiveCase) {
        val bytes = case.bytes()
        val expectedError = case.expect.error
        if (expectedError != null) {
            val error = assertFailsWith<ArchiveException> { ArchiveDecoder.decode(bytes) }
            assertEquals(expectedError.kind, error.kind.rawValue)
            assertEquals(expectedError.path, error.path)
            return
        }
        val archive = ArchiveDecoder.decode(bytes)
        val summary = assertNotNull(case.expect.summary)
        assertEquals(summary.sourceFormatVersion, archive.sourceFormatVersion)
        assertEquals(summary.formatVersion, archive.sourceFormatVersion)
        assertEquals(summary.exportedAt, ArchiveDates.format(archive.exportedAt))
        val app = summary.app
        assertEquals(ArchiveApp(app.name, app.platform, app.version, app.build), archive.app)
        val counts = archive.cabinet.summary
        assertEquals(summary.locations, counts.locations)
        assertEquals(summary.inCabinet, counts.inCabinet)
        assertEquals(summary.removed, counts.removed)
        assertEquals(summary.deleted, counts.deleted)
        assertEquals(summary.unknownExpiry, counts.unknownExpiry)
        assertEquals(summary.namedProducts, counts.namedProducts)
        assertEquals(summary.namedProductsNeedFill, archive.cabinet.namedProductsNeedFill)
        assertEquals(case.expect.needsBackup, archive.cabinet.needsBackup)
    }

    /**
     * The canonical writer reproduces the golden file of the current version byte for byte: the proof that both
     * platforms write the same bytes (the Swift suite runs the same check on the same file).
     */
    @Test
    fun theEncoderReproducesTheGoldenArchiveByteForByte() {
        val golden = ArchiveFixtureFiles.goldenArchive(ArchiveFormat.currentVersion)
        val archive = ArchiveDecoder.decode(golden)
        assertEquals(2, archive.sourceFormatVersion)
        assertContentEquals(golden, ArchiveEncoder.encode(archive))
    }

    /**
     * Every later version imports every earlier golden file (F9). Version 1 has no named products: they are filled from
     * the boxes after the import.
     */
    @TestFactory
    fun everyGoldenArchiveStillDecodes(): List<DynamicTest> = (1..ArchiveFormat.currentVersion).map { version ->
        dynamicTest("version $version") {
            val archive = ArchiveDecoder.decode(ArchiveFixtureFiles.goldenArchive(version))
            assertEquals(version, archive.sourceFormatVersion)
            assertEquals(version == 1, archive.cabinet.namedProductsNeedFill)
            assertEquals(version == 1, archive.cabinet.namedProducts.isEmpty())
        }
    }

    /** The version 1 golden is never rewritten: re-encoding it gives the same cabinet in the current version. */
    @Test
    fun theVersionOneGoldenReEncodesAsTheSameCabinetInVersionTwo() {
        val old = ArchiveDecoder.decode(ArchiveFixtureFiles.goldenArchive(1))
        val renewed = ArchiveDecoder.decode(ArchiveEncoder.encode(old))
        assertEquals(2, renewed.sourceFormatVersion)
        assertEquals(old.cabinet.locations, renewed.cabinet.locations)
        assertEquals(old.cabinet.boxes, renewed.cabinet.boxes)
        assertTrue(renewed.cabinet.namedProducts.isEmpty())
        assertFalse(renewed.cabinet.namedProductsNeedFill)
    }

    @Test
    fun everyShippedFormatVersionKeepsAGoldenArchive() {
        for (version in 1..ArchiveFormat.currentVersion) {
            val exists = ArchiveFixtureFiles.goldenArchiveExists(version)
            assertTrue(exists, "missing fixtures/archive/v$version/golden.medicabinet")
        }
    }

    @Test
    fun theCurrentVersionIsTheNumberOfReaders() {
        assertEquals(ArchiveDecoder.readers.size, ArchiveFormat.currentVersion)
        assertEquals(2, ArchiveFormat.currentVersion)
    }
}
