package com.medicabinet.app.reminders

import com.medicabinet.app.app.AppLog
import com.medicabinet.app.app.BootstrapState
import com.medicabinet.app.app.ExpiryPreferences
import com.medicabinet.core.Box
import com.medicabinet.core.Clock
import com.medicabinet.core.expiry.CalendarDay
import com.medicabinet.core.expiry.ExpiryDigest
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/**
 * What runs when the digest alarm fires (N5–N8, N12). It decides which month is due, never delivers a month twice,
 * counts the boxes **as of the digest day** (so a late alarm says what an on-time one would have), posts only when
 * something needs attention and notifications are allowed, records the month, and arms the next alarm — whatever
 * happened.
 *
 * Nothing about the cabinet is logged beyond the failure kind.
 */
class DigestDelivery(
    private val openCabinet: suspend () -> BootstrapState,
    private val preferences: ExpiryPreferences,
    private val access: NotificationAccess,
    private val notifier: DigestNotifier,
    private val scheduling: ReminderScheduling,
    private val clock: Clock,
    private val zone: () -> ZoneId
) {
    suspend fun deliver() {
        val now = clock.now()
        if (!preferences.reminderEnabled) {
            scheduling.arm()
            return
        }
        val due = ExpiryDigest.due(CalendarDay.from(now, zone()), CalendarDay.minutesSinceMidnight(now, zone()))
        val month = DigestMonth.key(due)
        if (preferences.lastDigestMonth == month) {
            scheduling.arm()
            return
        }
        val boxes = readBoxes()
        val delivered = boxes != null && notify(boxes, due)
        if (delivered) preferences.lastDigestMonth = month
        // A failure is retried later, not at once: a cabinet that cannot be read must not become a busy loop.
        scheduling.arm(notBefore = if (delivered) null else now.plus(ReminderScheduler.RETRY_AFTER))
    }

    private suspend fun readBoxes(): List<Box>? = try {
        when (val state = openCabinet()) {
            is BootstrapState.Ready -> state.environment.boxes.activeBoxes()
            is BootstrapState.Failed -> {
                AppLog.reminderUnavailable(state.failure)
                null
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        AppLog.reminderFailed("read", error)
        null
    }

    /** `true` when this month is done: posted, or nothing to say, or notifications are not allowed any more. */
    private fun notify(boxes: List<Box>, due: CalendarDay): Boolean {
        val window = preferences.window
        val counts = ExpiryDigest.counts(boxes, due, window)
        if (counts == null || !access.canPost()) return true
        return try {
            notifier.post(counts, window)
            true
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            AppLog.reminderFailed("notify", error)
            false
        }
    }
}
