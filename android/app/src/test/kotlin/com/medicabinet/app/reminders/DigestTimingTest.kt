package com.medicabinet.app.reminders

import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import org.junit.Test

class DigestTimingTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val auckland = ZoneId.of("Pacific/Auckland")
    private val losAngeles = ZoneId.of("America/Los_Angeles")

    private fun next(now: String, zone: ZoneId, last: String?): Instant =
        DigestTiming.nextTrigger(Instant.parse(now), zone, last)

    @Test
    fun theNextDigestIsTheFirstOfNextMonthAtNineLocal() {
        // January 15 in Paris (UTC+1): February 1 at 09:00 is 08:00 UTC.
        assertEquals(Instant.parse("2027-02-01T08:00:00Z"), next("2027-01-15T12:00:00Z", paris, "2027-01"))
    }

    @Test
    fun nineOClockFollowsTheZoneAcrossTheWorld() {
        val now = "2027-01-15T12:00:00Z"
        assertEquals(Instant.parse("2027-01-31T20:00:00Z"), next(now, auckland, "2027-01"))
        assertEquals(Instant.parse("2027-02-01T17:00:00Z"), next(now, losAngeles, "2027-01"))
    }

    @Test
    fun aTimeZoneChangeMovesTheDigestToNineInTheNewZone() {
        val now = "2027-01-15T12:00:00Z"
        val before = next(now, paris, "2027-01")
        val after = next(now, losAngeles, "2027-01")
        assertEquals(Instant.parse("2027-02-01T08:00:00Z"), before)
        assertEquals(Instant.parse("2027-02-01T17:00:00Z"), after)
    }

    @Test
    fun summerTimeIsHonoured() {
        // April 1 in Paris is CEST (UTC+2): 09:00 is 07:00 UTC.
        assertEquals(Instant.parse("2027-04-01T07:00:00Z"), next("2027-03-15T12:00:00Z", paris, "2027-03"))
    }

    @Test
    fun aMissedDigestOfThisMonthIsDueAtOnce() {
        val now = Instant.parse("2027-01-15T12:00:00Z")
        assertEquals(now, DigestTiming.nextTrigger(now, paris, "2026-12"))
        assertEquals(now, DigestTiming.nextTrigger(now, paris, null))
    }

    @Test
    fun aDeliveredDigestIsNotDueAgain() {
        val now = Instant.parse("2027-01-15T12:00:00Z")
        assertEquals(Instant.parse("2027-02-01T08:00:00Z"), DigestTiming.nextTrigger(now, paris, "2027-01"))
    }

    @Test
    fun onTheFirstBeforeNineTodaysDigestIsStillToCome() {
        // 08:00 in Paris on February 1 (07:00 UTC): the January digest is the last due, already delivered.
        assertEquals(
            Instant.parse("2027-02-01T08:00:00Z"),
            next("2027-02-01T07:00:00Z", paris, "2027-01")
        )
    }

    @Test
    fun onTheFirstAtNineTodaysDigestIsDueNow() {
        val now = "2027-02-01T08:00:00Z"
        assertEquals(Instant.parse(now), next(now, paris, "2027-01"))
        // Once delivered, the next one is March 1 at 09:00 (UTC+1).
        assertEquals(Instant.parse("2027-03-01T08:00:00Z"), next(now, paris, "2027-02"))
    }

    @Test
    fun theYearEndRollsOverToJanuary() {
        assertEquals(
            Instant.parse("2028-01-01T08:00:00Z"),
            next("2027-12-20T12:00:00Z", paris, "2027-12")
        )
    }

    @Test
    fun theSameInstantIsAnotherMonthInAnotherZone() {
        // 20:30 UTC on January 31: still January in Paris, already February 1 09:30 in Auckland (UTC+13).
        val now = Instant.parse("2027-01-31T20:30:00Z")
        assertEquals(Instant.parse("2027-02-01T08:00:00Z"), DigestTiming.nextTrigger(now, paris, "2027-01"))
        assertEquals(now, DigestTiming.nextTrigger(now, auckland, "2027-01"))
    }
}
