package com.medicabinet.core.scan

import java.time.Instant

/**
 * Decides which sightings of a code count as a new scan, so a box held in front of the camera counts once
 * while the next box (or the same box shown again after a pause) counts again.
 *
 * A payload is new when it was never seen, or was last seen `rescanGapMs` or more before. Every sighting
 * refreshes the last-seen time, so only a real absence re-arms a code.
 */
class ScanDebouncer(private val rescanGapMs: Long = DEFAULT_RESCAN_GAP_MS) {
    private val lastSeen = mutableMapOf<String, Long>()

    /** Feed every frame; returns the payloads that count as new scans, in the order they appear in [visible]. */
    fun observe(visible: List<String>, at: Instant): List<String> {
        val now = at.toEpochMilli()
        val scans = mutableListOf<String>()
        for (payload in visible.distinct()) {
            val seen = lastSeen[payload]
            if (seen == null || now - seen >= rescanGapMs) scans += payload
            lastSeen[payload] = now
        }
        lastSeen.values.removeAll { now - it > rescanGapMs * EVICTION_FACTOR }
        return scans
    }

    companion object {
        const val DEFAULT_RESCAN_GAP_MS = 1_000L

        /** Entries older than this many gaps are forgotten (bounded memory); they would count as new anyway. */
        private const val EVICTION_FACTOR = 10
    }
}
