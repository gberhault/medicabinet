package com.medicabinet.app.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.medicabinet.app.MainActivity
import com.medicabinet.app.R
import com.medicabinet.app.app.AppTab
import com.medicabinet.app.app.TabRouter
import com.medicabinet.core.expiry.DigestCounts
import com.medicabinet.core.expiry.ExpiryWindow

/** Shows the monthly digest. Takes the two counts and the window only: it is never given a name, a date or a place. */
interface DigestNotifier {
    fun post(counts: DigestCounts, window: ExpiryWindow)
}

/** What the notification says. */
data class DigestText(val title: String, val body: String)

class NotificationDigestNotifier(private val context: Context) : DigestNotifier {
    /** Two counts and the window in words: "2 boxes expired · 3 boxes expire within 60 days". Plural per language. */
    fun text(counts: DigestCounts, window: ExpiryWindow): DigestText {
        val resources = context.resources
        val expired = counts.expired.takeIf { it > 0 }?.let {
            resources.getQuantityString(R.plurals.reminder_digest_body_expired, it, it)
        }
        val soon = counts.expiringSoon.takeIf { it > 0 }?.let {
            resources.getQuantityString(R.plurals.reminder_digest_body_soon, it, it, window.days)
        }
        val body = if (expired != null && soon != null) {
            context.getString(R.string.reminder_digest_body_both, expired, soon)
        } else {
            expired ?: soon.orEmpty()
        }
        return DigestText(context.getString(R.string.reminder_digest_title), body)
    }

    // The permission is checked by the delivery rule (and again by the system, which drops a refused notification).
    @SuppressLint("MissingPermission")
    override fun post(counts: DigestCounts, window: ExpiryWindow) {
        val text = text(counts, window)
        val notification = NotificationCompat.Builder(context, ReminderChannel.ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openExpiring())
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Opens the app on the Expiring tab (N9). Immutable, private to this app. */
    private fun openExpiring(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(TabRouter.EXTRA_DESTINATION, AppTab.EXPIRING.route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            TAP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        /** One digest at a time: a new month's replaces an unread old one. */
        const val NOTIFICATION_ID = 2501
        const val TAP_REQUEST_CODE = 2502
    }
}
