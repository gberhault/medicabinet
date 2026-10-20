package com.medicabinet.app.reminders

import android.content.Context

/** The device-facing parts of the reminder, injected so tests run without a notification manager. */
class ReminderServices(val access: NotificationAccess, val scheduling: ReminderScheduling) {
    companion object {
        fun live(context: Context): ReminderServices =
            ReminderServices(SystemNotificationAccess(context.applicationContext), ReminderScheduling { })
    }
}
