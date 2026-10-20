package com.medicabinet.app.reminders

import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.expiry.DigestCounts
import com.medicabinet.core.expiry.ExpiryWindow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DigestDeliveryTest {
    private val paris = ZoneId.of("Europe/Paris")

    /** 2027-03-20 12:00 UTC: the March digest (the 1st at 09:00) is the one due. */
    private fun kit(now: String = "2027-03-20T12:00:00Z", zone: ZoneId = ZoneId.of("UTC")) =
        DeliveryKit(Instant.parse(now), zone)

    private fun DeliveryKit.run() = runBlocking { delivery.deliver() }

    @Test
    fun postsTheCountsAndRecordsTheMonthThenArmsTheNextDigest() {
        val kit = kit()
        kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        kit.box("Efferalgan", ExpiryDate.of(2027, 4, 10))
        kit.box("Spasfon", ExpiryDate.of(2030, 1, null))
        kit.run()
        assertEquals(listOf(DigestCounts(expired = 1, expiringSoon = 1) to ExpiryWindow.DAYS_60), kit.recorded.posted)
        assertEquals("2027-03", kit.preferences.lastDigestMonth)
        assertEquals(listOf<Instant?>(null), kit.scheduling.calls)
    }

    @Test
    fun theCountsAreThoseOfTheDigestDayNotOfTheDeliveryTime() {
        // Delivered on March 20; the digest day is March 1. A box expiring on March 10 was still expiring soon then.
        val kit = kit()
        kit.box("Doliprane", ExpiryDate.of(2027, 3, 10))
        kit.run()
        assertEquals(listOf(DigestCounts(expired = 0, expiringSoon = 1)), kit.recorded.posted.map { it.first })
    }

    @Test
    fun aLateDeliveryAndAnOnTimeOneSayTheSame() {
        val onTime = kit("2027-03-01T09:30:00Z")
        val late = kit("2027-03-27T09:30:00Z")
        listOf(onTime, late).forEach {
            it.box("Doliprane", ExpiryDate.of(2027, 3, 10))
            it.box("Efferalgan", ExpiryDate.of(2027, 2, 10))
            it.run()
        }
        assertEquals(onTime.recorded.posted, late.recorded.posted)
        assertEquals(DigestCounts(expired = 1, expiringSoon = 1), late.recorded.posted.single().first)
    }

    @Test
    fun theSameMonthIsNeverDeliveredTwice() {
        val kit = kit()
        kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        kit.run()
        kit.run()
        kit.run()
        assertEquals(1, kit.recorded.posted.size)
        // Each run still arms the next alarm.
        assertEquals(3, kit.scheduling.arms)
    }

    @Test
    fun aFreshDigestIsDeliveredTheNextMonth() {
        val kit = kit()
        kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        kit.run()
        kit.clock.advance(Duration.ofDays(15))
        kit.run()
        assertEquals(2, kit.recorded.posted.size)
        assertEquals("2027-04", kit.preferences.lastDigestMonth)
    }

    @Test
    fun nothingToReportPostsNothingYetTheMonthIsDone() {
        val kit = kit()
        kit.box("Pansements", expiry = null)
        kit.box("Spasfon", ExpiryDate.of(2030, 1, null))
        kit.run()
        assertTrue(kit.recorded.posted.isEmpty())
        assertEquals("2027-03", kit.preferences.lastDigestMonth)
    }

    @Test
    fun unknownExpiriesAreNotCounted() {
        val kit = kit()
        kit.box("Pansements", expiry = null)
        kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        kit.run()
        assertEquals(DigestCounts(expired = 1, expiringSoon = 0), kit.recorded.posted.single().first)
    }

    @Test
    fun removedBoxesAreNotCounted() {
        val kit = kit()
        val thrown = kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        kit.box("Efferalgan", ExpiryDate.of(2027, 2, null))
        kit.remove(thrown.id)
        kit.run()
        assertEquals(1, kit.recorded.posted.single().first.expired)
    }

    @Test
    fun theWindowIsTheCurrentSetting() {
        val kit = kit()
        kit.box("Doliprane", ExpiryDate.of(2027, 5, 15))
        // 75 days after March 1: outside 60, inside 90.
        kit.run()
        assertTrue(kit.recorded.posted.isEmpty())
        val ninety = kit("2027-03-20T12:00:00Z")
        ninety.preferences.window = ExpiryWindow.DAYS_90
        ninety.box("Doliprane", ExpiryDate.of(2027, 5, 15))
        ninety.run()
        assertEquals(ExpiryWindow.DAYS_90, ninety.recorded.posted.single().second)
        assertEquals(1, ninety.recorded.posted.single().first.expiringSoon)
    }

    @Test
    fun withNotificationsBlockedNothingIsPostedAndTheMonthIsSkipped() {
        val kit = kit()
        kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        kit.access.canPostNow = false
        kit.run()
        assertTrue(kit.recorded.posted.isEmpty())
        assertEquals("2027-03", kit.preferences.lastDigestMonth)
        assertEquals(1, kit.scheduling.arms)
    }

    @Test
    fun withTheReminderOffNothingHappensButTheAlarmIsCancelledThroughArm() {
        val kit = kit()
        kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        kit.preferences.reminderEnabled = false
        kit.run()
        assertTrue(kit.recorded.posted.isEmpty())
        assertNull(kit.preferences.lastDigestMonth)
        assertEquals(listOf<Instant?>(null), kit.scheduling.calls)
    }

    @Test
    fun anUnreadableCabinetPostsNothingKeepsTheMonthOpenAndRetriesLater() {
        val kit = kit()
        kit.failToOpen()
        kit.run()
        assertTrue(kit.recorded.posted.isEmpty())
        assertNull(kit.preferences.lastDigestMonth)
        // Re-armed, but never sooner than the retry delay: no busy loop on a cabinet that stays unreadable.
        assertEquals(listOf<Instant?>(kit.clock.now().plus(ReminderScheduler.RETRY_AFTER)), kit.scheduling.calls)
    }

    @Test
    fun anExceptionWhileOpeningIsTheSameFailure() {
        val kit = kit()
        kit.openFailure = IllegalStateException("disk")
        kit.run()
        assertTrue(kit.recorded.posted.isEmpty())
        assertNull(kit.preferences.lastDigestMonth)
        assertEquals(listOf<Instant?>(kit.clock.now().plus(ReminderScheduler.RETRY_AFTER)), kit.scheduling.calls)
    }

    @Test
    fun aFailedNotificationIsRetriedNotRecorded() {
        val kit = DeliveryKit(Instant.parse("2027-03-20T12:00:00Z"), notifier = RecordingNotifier(SecurityException()))
        kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        kit.run()
        assertNull(kit.preferences.lastDigestMonth)
        assertEquals(listOf<Instant?>(kit.clock.now().plus(ReminderScheduler.RETRY_AFTER)), kit.scheduling.calls)
    }

    @Test
    fun theDueMonthFollowsTheTimeZone() {
        // 22:00 UTC on March 31 is 00:00 on April 1 in Paris (summer time): March's digest is still the last due.
        val parisKit = kit("2027-03-31T22:00:00Z", paris)
        parisKit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        parisKit.run()
        assertEquals("2027-03", parisKit.preferences.lastDigestMonth)
        // The same instant in Auckland (UTC+13) is April 1 11:00: the April digest is due.
        val auckland = kit("2027-03-31T22:00:00Z", ZoneId.of("Pacific/Auckland"))
        auckland.box("Doliprane", ExpiryDate.of(2027, 2, null))
        auckland.run()
        assertEquals("2027-04", auckland.preferences.lastDigestMonth)
    }

    @Test
    fun aThousandBoxesAreCountedWellInsideTheReceiverBudget() {
        val kit = kit()
        repeat(1_000) { index ->
            kit.box("Box $index", ExpiryDate.of(2027, 1 + index % 12, null))
        }
        val timings = (1..5).map {
            kit.preferences.lastDigestMonth = null
            val start = System.nanoTime()
            kit.run()
            Duration.ofNanos(System.nanoTime() - start)
        }.sorted()
        // Budget: the receiver has ten seconds; one second is generous for one query and a count.
        assertTrue(timings[timings.size / 2] < Duration.ofSeconds(1), "median ${timings[timings.size / 2]}")
    }
}
