package com.medicabinet.app.reminders

import androidx.lifecycle.ViewModel
import com.medicabinet.app.app.ExpiryPreferences
import com.medicabinet.core.Clock
import com.medicabinet.core.expiry.CalendarDay
import com.medicabinet.core.expiry.ExpiryDigest
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the monthly reminder is working. */
enum class ReminderState {
    OFF,

    /** The user turned it on and the system lets notifications through. */
    ON,

    /** The user turned it on but the system no longer allows notifications: never shown as "on". */
    BLOCKED
}

/** Why the reminder cannot work, to be said in words (with **Open Settings**). */
enum class ReminderNotice {
    NONE,

    /** The app's notifications are switched off in the system settings (or its channel is). */
    BLOCKED,

    /** The user refused the permission prompt. */
    DENIED
}

/** What the UI must do after **Turn on**. */
enum class TurnOnResult {
    /** Done: the reminder is on and armed. */
    ENABLED,

    /** Show the system permission prompt, then call [ReminderModel.onPermissionResult]. */
    NEEDS_PERMISSION,

    /** Notifications are off in the system settings: nothing to prompt, [ReminderModel.notice] says so. */
    BLOCKED
}

/** Arms (or cancels) the platform alarm of the next digest; the scheduler re-reads the preferences each time. */
fun interface ReminderScheduling {
    fun arm()
}

/**
 * The monthly-reminder setting shared by the Expiring card and Settings (N1–N4, N13): the user's choice
 * (`reminder.enabled`) combined with what the system allows. [refresh] re-reads the system whenever the app comes to
 * the foreground, so a permission revoked in the system settings shows as [ReminderState.BLOCKED].
 *
 * Nothing here asks for the permission by itself: only [turnOn], called by the user, can lead to the prompt.
 */
class ReminderModel(
    private val preferences: ExpiryPreferences,
    private val access: NotificationAccess,
    private val scheduling: ReminderScheduling,
    private val clock: Clock,
    private val zone: () -> ZoneId
) : ViewModel() {
    private var refused = ReminderNotice.NONE
    private val mutableState = MutableStateFlow(ReminderState.OFF)
    private val mutableNotice = MutableStateFlow(ReminderNotice.NONE)
    private val mutableCard = MutableStateFlow(false)

    val state: StateFlow<ReminderState> = mutableState.asStateFlow()

    /** Set while the reminder is blocked or the user's last attempt to turn it on was refused. */
    val notice: StateFlow<ReminderNotice> = mutableNotice.asStateFlow()

    /** The Expiring card offers the reminder: off and **Not now** not chosen. */
    val offersCard: StateFlow<Boolean> = mutableCard.asStateFlow()

    init {
        publish()
    }

    /** Reads the system again (the app is in the foreground). */
    fun refresh() = publish()

    fun turnOn(): TurnOnResult {
        refused = ReminderNotice.NONE
        publish()
        return when {
            access.canPost() -> {
                enable()
                TurnOnResult.ENABLED
            }
            access.needsRuntimePermission() -> TurnOnResult.NEEDS_PERMISSION
            else -> {
                refused = ReminderNotice.BLOCKED
                publish()
                TurnOnResult.BLOCKED
            }
        }
    }

    /** The answer of the system permission prompt. A refusal leaves the reminder off and says why. */
    fun onPermissionResult(granted: Boolean) {
        if (granted && access.canPost()) {
            refused = ReminderNotice.NONE
            enable()
        } else {
            refused = if (granted) ReminderNotice.BLOCKED else ReminderNotice.DENIED
            publish()
        }
    }

    /** Turns the reminder off: the pending alarm is cancelled (N13). */
    fun turnOff() {
        refused = ReminderNotice.NONE
        preferences.reminderEnabled = false
        scheduling.arm()
        publish()
    }

    /** **Not now** on the Expiring card: remembered for good. */
    fun dismissCard() {
        preferences.reminderCardDismissed = true
        publish()
    }

    private fun enable() {
        // Only the digests still to come: turning it on mid-month must not fire this month's digest at once.
        val now = clock.now()
        val today = CalendarDay.from(now, zone())
        val minutes = CalendarDay.minutesSinceMidnight(now, zone())
        preferences.lastDigestMonth = DigestMonth.key(ExpiryDigest.due(today, minutes))
        preferences.reminderEnabled = true
        scheduling.arm()
        publish()
    }

    private fun publish() {
        val enabled = preferences.reminderEnabled
        val state = when {
            !enabled -> ReminderState.OFF
            access.canPost() -> ReminderState.ON
            else -> ReminderState.BLOCKED
        }
        mutableState.value = state
        mutableNotice.value = if (state == ReminderState.BLOCKED) ReminderNotice.BLOCKED else refused
        mutableCard.value = !enabled && !preferences.reminderCardDismissed
    }
}
