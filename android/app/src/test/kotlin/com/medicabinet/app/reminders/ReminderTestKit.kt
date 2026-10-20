package com.medicabinet.app.reminders

import com.medicabinet.app.app.InMemoryExpiryPreferences
import com.medicabinet.core.testing.MutableClock
import java.time.Instant
import java.time.ZoneOffset

class FakeNotificationAccess(var canPostNow: Boolean = false, var permissionMissing: Boolean = true) :
    NotificationAccess {
    override fun canPost(): Boolean = canPostNow

    override fun needsRuntimePermission(): Boolean = permissionMissing

    /** The user grants the permission in the system prompt. */
    fun grant() {
        canPostNow = true
        permissionMissing = false
    }
}

class RecordingScheduling : ReminderScheduling {
    var arms = 0
        private set

    override fun arm() {
        arms++
    }
}

/** A reminder model on fakes; "now" is 2027-01-15 12:00 UTC unless the test moves the clock. */
class ReminderTestKit(
    val access: FakeNotificationAccess = FakeNotificationAccess(),
    now: Instant = Instant.parse("2027-01-15T12:00:00Z")
) {
    val preferences = InMemoryExpiryPreferences()
    val scheduling = RecordingScheduling()
    val clock = MutableClock(now)

    fun model() = ReminderModel(preferences, access, scheduling, clock) { ZoneOffset.UTC }
}
