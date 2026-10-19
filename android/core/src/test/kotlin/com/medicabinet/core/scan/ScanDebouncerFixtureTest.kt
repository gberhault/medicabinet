package com.medicabinet.core.scan

import com.medicabinet.core.testing.ScanDebounceCase
import com.medicabinet.core.testing.SharedFixtures
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** Runs every case of the repository-wide `fixtures/scan-debounce.json`, which the Swift core also runs. */
class ScanDebouncerFixtureTest {
    private val start = Instant.ofEpochSecond(1_000_000)

    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        assertTrue(SharedFixtures.scanDebounceCases().size >= 15)
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.scanDebounceCases().map { case ->
        dynamicTest(case.name) {
            val debouncer = ScanDebouncer(case.rescanGapMs.toLong())
            val scans = case.frames.flatMap { frame ->
                debouncer.observe(frame.visible, start.plusMillis(frame.t)).map { ScanDebounceCase.Scan(frame.t, it) }
            }
            assertEquals(case.expectScans, scans)
        }
    }

    @Test
    fun theDefaultGapIsOneSecond() {
        assertEquals(1_000L, ScanDebouncer.DEFAULT_RESCAN_GAP_MS)
        val debouncer = ScanDebouncer()
        assertEquals(listOf("A"), debouncer.observe(listOf("A"), start))
        assertTrue(debouncer.observe(listOf("A"), start.plusMillis(999)).isEmpty())
        assertEquals(listOf("A"), debouncer.observe(listOf("A"), start.plusMillis(999 + 1_000)))
    }

    @Test
    fun aClockGoingBackwardsNeverRecounts() {
        val debouncer = ScanDebouncer()
        debouncer.observe(listOf("A"), start)
        assertTrue(debouncer.observe(listOf("A"), start.minusSeconds(5)).isEmpty())
    }

    @Test
    fun manyFramesOfManyPayloadsStayFast() {
        val debouncer = ScanDebouncer()
        val payloads = List(5) { "payload-$it" }
        val begin = System.nanoTime()
        repeat(10_000) { debouncer.observe(payloads, start.plusMillis(it * 33L)) }
        val elapsedMs = (System.nanoTime() - begin) / 1_000_000
        assertTrue(elapsedMs < 500, "10 000 frames took $elapsedMs ms")
    }
}
