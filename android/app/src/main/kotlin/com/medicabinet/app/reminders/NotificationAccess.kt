package com.medicabinet.app.reminders

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** What the system lets the app do with notifications right now. Read again whenever the app is in the foreground. */
interface NotificationAccess {
    /** A notification can be shown: permission granted where it exists, app switch on, channel not blocked. */
    fun canPost(): Boolean

    /** Android 13+ and the `POST_NOTIFICATIONS` permission is not granted: the system prompt can be shown. */
    fun needsRuntimePermission(): Boolean
}

/**
 * API 33+: the runtime permission. API 26–32: no permission exists, so the app-level switch and the channel's
 * importance are the only things that can stop a notification.
 */
class SystemNotificationAccess(private val context: Context) : NotificationAccess {
    private val manager = NotificationManagerCompat.from(context)

    override fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

    override fun canPost(): Boolean =
        !needsRuntimePermission() && manager.areNotificationsEnabled() && !channelBlocked()

    private fun channelBlocked(): Boolean = manager.getNotificationChannelCompat(ReminderChannel.ID)?.importance ==
        NotificationManagerCompat.IMPORTANCE_NONE
}
