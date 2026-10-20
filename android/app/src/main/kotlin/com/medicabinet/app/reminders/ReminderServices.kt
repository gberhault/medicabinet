package com.medicabinet.app.reminders

import android.content.Context
import com.medicabinet.app.MediCabinetApplication
import com.medicabinet.app.app.BootstrapState
import com.medicabinet.app.app.ExpiryPreferences
import com.medicabinet.core.SystemClock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The device-facing parts of the reminder — permission state, the alarm, the digest delivery — built once by the
 * application and injected, so tests run with fakes and without a real alarm manager or notification manager.
 * [io] is where the receivers run their work (they use `goAsync`, never the main thread).
 */
class ReminderServices(
    val access: NotificationAccess,
    val scheduling: ReminderScheduling,
    val delivery: DigestDelivery,
    val io: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        /** [openCabinet] is the application's one cabinet opening, shared with the launch screen. */
        fun live(
            context: Context,
            preferences: ExpiryPreferences,
            openCabinet: suspend () -> BootstrapState
        ): ReminderServices {
            val app = context.applicationContext
            val access = SystemNotificationAccess(app)
            val zone = { ZoneId.systemDefault() }
            val scheduler = ReminderScheduler(app, preferences, access, SystemClock, zone)
            val delivery = DigestDelivery(
                openCabinet,
                preferences,
                access,
                NotificationDigestNotifier(app),
                scheduler,
                SystemClock,
                zone
            )
            return ReminderServices(access, scheduler, delivery)
        }
    }
}

/** The application's reminder services (the receivers and the main screen share them). */
fun Context.reminderServices(): ReminderServices = (applicationContext as MediCabinetApplication).reminderServices
