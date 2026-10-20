package com.medicabinet.app.reminders

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** The system screen where this app's notifications are switched on (**Open Settings**). */
fun notificationSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Opens it; a device with no such screen leaves the user where they are (the explanation stays visible). */
fun openNotificationSettings(context: Context) {
    try {
        context.startActivity(notificationSettingsIntent(context))
    } catch (_: ActivityNotFoundException) {
        // Nothing to open; the explanation text stays on screen.
    }
}
