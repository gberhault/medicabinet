package com.medicabinet.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.medicabinet.app.app.ExpiryPreferences
import com.medicabinet.core.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The one alarm of the next digest. It is an inexact alarm (`setWindow`, one hour from 09:00): no exact-alarm
 * permission and no dependency, at the price of a digest that can arrive later (under Doze) — the delivery rule
 * computes the counts for the digest day, so a late one says the same as an on-time one.
 *
 * [arm] is idempotent: one immutable `PendingIntent` with a constant request code, so arming twice replaces the
 * alarm. It runs at app start and foreground, on every reminder change, on boot, app update, time-zone and clock
 * change, and after every digest.
 */
class ReminderScheduler(
    private val context: Context,
    private val preferences: ExpiryPreferences,
    private val access: NotificationAccess,
    private val clock: Clock,
    private val zone: () -> ZoneId
) : ReminderScheduling {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override fun arm(notBefore: Instant?) {
        if (!preferences.reminderEnabled || !access.canPost()) {
            cancel()
            return
        }
        val computed = DigestTiming.nextTrigger(clock.now(), zone(), preferences.lastDigestMonth)
        val target = if (notBefore != null && notBefore.isAfter(computed)) notBefore else computed
        alarms.setWindow(AlarmManager.RTC_WAKEUP, target.toEpochMilli(), WINDOW.toMillis(), pendingIntent())
    }

    private fun cancel() {
        val pending = pendingIntent()
        alarms.cancel(pending)
        pending.cancel()
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, DigestAlarmReceiver::class.java).setAction(ACTION_DIGEST)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val ACTION_DIGEST = "com.medicabinet.app.action.DIGEST"
        const val REQUEST_CODE = 2501

        /** The system may deliver the digest any time in the hour after 09:00. */
        val WINDOW: Duration = Duration.ofHours(1)

        /** A failed delivery (cabinet unreadable) is tried again after this long. */
        val RETRY_AFTER: Duration = Duration.ofHours(1)
    }
}
