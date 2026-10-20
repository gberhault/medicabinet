package com.medicabinet.app.reminders

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import com.medicabinet.app.app.InMemoryExpiryPreferences
import com.medicabinet.core.testing.MutableClock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val preferences = InMemoryExpiryPreferences().also {
        it.reminderEnabled = true
        it.lastDigestMonth = "2027-01"
    }
    private val access = FakeNotificationAccess(canPostNow = true, permissionMissing = false)
    private val clock = MutableClock(Instant.parse("2027-01-15T12:00:00Z"))
    private var zone: ZoneId = ZoneId.of("Europe/Paris")
    private val scheduler by lazy { ReminderScheduler(context, preferences, access, clock) { zone } }

    @Test
    fun armsOneInexactWakeupAlarmAtNineOnTheFirstWithAOneHourWindow() {
        scheduler.arm()
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(Instant.parse("2027-02-01T08:00:00Z").toEpochMilli(), alarm.triggerAtMs)
        assertEquals(Duration.ofHours(1).toMillis(), alarm.windowLengthMs)
        // Inexact: a real window, no exact-while-idle request.
        assertTrue(alarm.windowLengthMs > 0)
        assertFalse(alarm.isAllowWhileIdle)
    }

    @Test
    fun theAlarmIsOneImmutablePendingIntentOnTheDigestReceiver() {
        scheduler.arm()
        val operation = alarms.scheduledAlarms.single().operation
        val shadow = shadowOf(operation)
        assertTrue(shadow.isBroadcastIntent)
        assertEquals(ReminderScheduler.REQUEST_CODE, shadow.requestCode)
        assertTrue(shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = shadow.savedIntent
        assertEquals(ReminderScheduler.ACTION_DIGEST, intent.action)
        assertEquals(ComponentName(context, DigestAlarmReceiver::class.java), intent.component)
    }

    @Test
    fun armingAgainReplacesTheAlarm() {
        repeat(3) { scheduler.arm() }
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun aMissedDigestOfThisMonthIsDueNow() {
        preferences.lastDigestMonth = "2026-12"
        scheduler.arm()
        assertEquals(clock.now().toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)
    }

    @Test
    fun theAlarmFollowsATimeZoneChange() {
        scheduler.arm()
        val inParis = alarms.scheduledAlarms.single().triggerAtMs
        zone = ZoneId.of("America/Los_Angeles")
        scheduler.arm()
        val inLosAngeles = alarms.scheduledAlarms.single().triggerAtMs
        assertEquals(Instant.parse("2027-02-01T08:00:00Z").toEpochMilli(), inParis)
        assertEquals(Instant.parse("2027-02-01T17:00:00Z").toEpochMilli(), inLosAngeles)
    }

    @Test
    fun aRetryIsNeverSoonerThanRequested() {
        preferences.lastDigestMonth = "2026-12"
        val later = clock.now().plus(Duration.ofHours(1))
        scheduler.arm(notBefore = later)
        assertEquals(later.toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)
    }

    @Test
    fun aRetryDoesNotDelayAnAlarmThatIsAlreadyLater() {
        scheduler.arm(notBefore = clock.now().plus(Duration.ofHours(1)))
        assertEquals(Instant.parse("2027-02-01T08:00:00Z").toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)
    }

    @Test
    fun withTheReminderOffTheAlarmIsCancelled() {
        scheduler.arm()
        assertEquals(1, alarms.scheduledAlarms.size)
        preferences.reminderEnabled = false
        scheduler.arm()
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun withNotificationsBlockedTheAlarmIsCancelled() {
        scheduler.arm()
        access.canPostNow = false
        scheduler.arm()
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun nothingIsScheduledWhenTheReminderWasNeverOn() {
        preferences.reminderEnabled = false
        scheduler.arm()
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
