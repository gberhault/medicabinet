package com.medicabinet.core.archive

import com.medicabinet.core.testing.SyntheticArchive
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.junit.jupiter.api.Test

/**
 * Timing budgets (design §9) for a 1 000-box cabinet, asserted on the best of several runs: a busy machine slows some
 * runs but not all, and the best one is what the code costs (a JVM also needs a few runs to warm up). The budgets are
 * several times what the JVM measures, and a quarter of the 2 s export/import target.
 */
class ArchivePerformanceTest {
    private fun best(runs: Int = 5, work: () -> Unit): Duration = (1..runs).map { measureTime(work) }.min()

    @Test
    fun encodingAThousandBoxesStaysWithinBudget() {
        val archive = ArchiveDecoder.decode(SyntheticArchive.json(3, 1_000).toByteArray())
        val time = best { ArchiveEncoder.encode(archive) }
        assertTrue(time < 500.milliseconds, "encoding took $time")
    }

    @Test
    fun decodingAThousandBoxesStaysWithinBudget() {
        val bytes = SyntheticArchive.json(3, 1_000).toByteArray()
        val time = best { ArchiveDecoder.decode(bytes) }
        assertTrue(time < 500.milliseconds, "decoding took $time")
    }

    /** A guard against quadratic behaviour only: a quadratic decoder would take minutes on the largest cabinet. */
    @Test
    fun decodingTheLargestAllowedCabinetFinishesInTime() {
        val limits = ArchiveLimits.DEFAULT
        val bytes = SyntheticArchive.json(limits.maxLocations, limits.maxBoxes).toByteArray()
        assertTrue(bytes.size < limits.maxFileBytes)
        val time = best(runs = 1) { ArchiveDecoder.decode(bytes) }
        assertTrue(time < 60.seconds, "decoding took $time")
    }
}
