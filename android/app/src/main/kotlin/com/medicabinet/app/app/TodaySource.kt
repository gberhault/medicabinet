package com.medicabinet.app.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.medicabinet.core.expiry.CalendarDay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * "Today" as a calendar day: emitted when collected and again whenever it changes (midnight, a time-zone change, a
 * manual clock change), so every status, the tab badge and the reminders are brought up to date (S9).
 */
fun interface TodaySource {
    fun days(): Flow<CalendarDay>
}

/**
 * Emits each time the device's time zone or clock is changed by the user or the network, and at midnight (the date
 * changes, which a delay alone misses when the device slept through it). The broadcasts are system broadcasts: a
 * runtime receiver that is not exported still gets them. Registered only while the flow is collected.
 */
fun systemTimeChanges(context: Context): Flow<Unit> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(Unit)
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_DATE_CHANGED)
    }
    val app = context.applicationContext
    ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    awaitClose { app.unregisterReceiver(receiver) }
}
