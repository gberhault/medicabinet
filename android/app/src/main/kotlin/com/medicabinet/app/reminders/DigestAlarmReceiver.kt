package com.medicabinet.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.medicabinet.app.app.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs [work] off the main thread inside the receiver's ten-second budget (`goAsync`), then finishes the broadcast —
 * also when the work fails (only the failure kind is logged).
 */
internal fun BroadcastReceiver.runAsync(context: Context, work: suspend (ReminderServices) -> Unit) {
    val pending = goAsync()
    val services = context.reminderServices()
    CoroutineScope(SupervisorJob() + services.io).launch {
        try {
            work(services)
        } catch (error: CancellationException) {
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            AppLog.reminderFailed("receiver", error)
        } finally {
            pending.finish()
        }
    }
}

/**
 * The monthly alarm went off (any time in the hour after 09:00 on the 1st, later under Doze): count, post, record the
 * month, arm the next one. Not exported: only this app's own `PendingIntent` reaches it.
 */
class DigestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_DIGEST) return
        runAsync(context) { it.delivery.deliver() }
    }
}
