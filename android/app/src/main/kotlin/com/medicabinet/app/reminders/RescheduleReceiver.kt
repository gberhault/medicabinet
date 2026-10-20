package com.medicabinet.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alarms do not survive a reboot or an app update, and a time-zone or clock change moves 09:00: each of these system
 * broadcasts arms the digest again (N12, N14). System broadcasts reach a receiver that is not exported; anything else
 * is ignored.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        runAsync(context) { it.scheduling.arm() }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED
        )
    }
}
