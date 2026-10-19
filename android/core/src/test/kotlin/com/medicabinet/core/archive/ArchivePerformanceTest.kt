package com.medicabinet.core.archive

import com.medicabinet.core.testing.SyntheticArchive
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.junit.jupiter.api.Test

/**
 * Timing budgets (design §9) for a 1 000-box cabinet, asserted on the median of several runs so one slow run does
 * not fail them. They are several times what the JVM measures once warm, a quarter of the 2 s export/import target.
 */
class ArchivePerformanceTest {
    private fun median(runs: Int = 5, work: () -> Unit): Duration {
        val times = (1..runs).map { measureTime(work) }.sorted()
        return times[times.size / 2]
    }

    @Test
    fun encodingAThousandBoxesStaysWithinBudget() {
        val archive = ArchiveDecoder.decode(SyntheticArchive.json(3, 1_000).toByteArray())
        val time = median { ArchiveEncoder.encode(archive) }
        assertTrue(time < 500.milliseconds, "encoding took $time")
    }

    @Test
    fun decodingAThousandBoxesStaysWithinBudget() {
        val bytes = SyntheticArchive.json(3, 1_000).toByteArray()
        val time = median { ArchiveDecoder.decode(bytes) }
        assertTrue(time < 500.milliseconds, "decoding took $time")
    }

    @Test
    fun decodingTheLargestAllowedCabinetFinishesInTime() {
        val limits = ArchiveLimits.DEFAULT
        val bytes = SyntheticArchive.json(limits.maxLocations, limits.maxBoxes).toByteArray()
        assertTrue(bytes.size < limits.maxFileBytes)
        val time = median(runs = 1) { ArchiveDecoder.decode(bytes) }
        assertTrue(time < 10.seconds, "decoding took $time")
    }
}
