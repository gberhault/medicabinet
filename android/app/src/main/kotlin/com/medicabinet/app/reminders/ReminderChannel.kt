package com.medicabinet.app.reminders

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.medicabinet.app.R

/** The notification channel of the monthly digest (Android 8+ requires one; older versions ignore it). */
object ReminderChannel {
    const val ID = "expiry_reminders"

    /** Creates the channel with its localised name and description. Idempotent: run at every app start. */
    fun ensure(context: Context) {
        val channel = NotificationChannelCompat.Builder(ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.reminder_channel_name))
            .setDescription(context.getString(R.string.reminder_channel_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}
