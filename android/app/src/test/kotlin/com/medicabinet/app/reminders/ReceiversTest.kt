package com.medicabinet.app.reminders

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import com.medicabinet.app.MediCabinetApplication
import com.medicabinet.core.ExpiryDate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReceiversTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val context: Context get() = app

    private fun install(kit: DeliveryKit): ReminderServices =
        ReminderServices(kit.access, kit.scheduling, kit.delivery, Dispatchers.Unconfined).also {
            (app as MediCabinetApplication).reminderServicesOverride = it
        }

    private fun digestIntent(action: String? = ReminderScheduler.ACTION_DIGEST) =
        Intent(context, DigestAlarmReceiver::class.java).setAction(action)

    private fun send(intent: Intent) {
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun theDigestAlarmPostsTheCountsForTheDueMonthAndArmsTheNextOne() {
        val kit = DeliveryKit(Instant.parse("2027-03-20T12:00:00Z"), notifier = NotificationDigestNotifier(context))
        kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        install(kit)
        send(digestIntent())
        assertEquals(1, notifications().size)
        assertEquals("2027-03", kit.preferences.lastDigestMonth)
        assertEquals(1, kit.scheduling.arms)
    }

    @Test
    fun aSecondAlarmForTheSameMonthPostsNothingMoreButArmsAgain() {
        val kit = DeliveryKit(Instant.parse("2027-03-20T12:00:00Z"), notifier = NotificationDigestNotifier(context))
        kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        install(kit)
        send(digestIntent())
        send(digestIntent())
        assertEquals(1, notifications().size)
        assertEquals(2, kit.scheduling.arms)
    }

    @Test
    fun aCabinetThatCannotBeOpenedPostsNothingAndSchedulesARetry() {
        val kit = DeliveryKit(Instant.parse("2027-03-20T12:00:00Z"), notifier = NotificationDigestNotifier(context))
        kit.failToOpen()
        install(kit)
        send(digestIntent())
        assertTrue(notifications().isEmpty())
        assertNull(kit.preferences.lastDigestMonth)
        assertNotNull(kit.scheduling.calls.single())
    }

    @Test
    fun anIntentWithAnotherActionIsIgnored() {
        val kit = DeliveryKit(Instant.parse("2027-03-20T12:00:00Z"), notifier = NotificationDigestNotifier(context))
        kit.box("Doliprane", ExpiryDate.of(2027, 2, null))
        install(kit)
        DigestAlarmReceiver().onReceive(context, digestIntent("com.example.other"))
        assertTrue(notifications().isEmpty())
        assertEquals(0, kit.scheduling.arms)
    }

    @Test
    fun everyRescheduleBroadcastArmsTheAlarmAgain() {
        val kit = DeliveryKit(Instant.parse("2027-03-20T12:00:00Z"))
        install(kit)
        RescheduleReceiver.ACTIONS.forEach { action -> send(Intent(action)) }
        assertEquals(RescheduleReceiver.ACTIONS.size, kit.scheduling.arms)
    }

    @Test
    fun theFourRescheduleActionsAreBootUpdateZoneAndClock() {
        assertEquals(
            setOf(
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.MY_PACKAGE_REPLACED",
                "android.intent.action.TIMEZONE_CHANGED",
                "android.intent.action.TIME_SET"
            ),
            RescheduleReceiver.ACTIONS
        )
    }

    @Test
    fun anUnrelatedBroadcastDoesNotArm() {
        val kit = DeliveryKit(Instant.parse("2027-03-20T12:00:00Z"))
        install(kit)
        RescheduleReceiver().onReceive(context, Intent("android.intent.action.AIRPLANE_MODE"))
        assertEquals(0, kit.scheduling.arms)
    }

    @Test
    fun theManifestRegistersTheRescheduleReceiverForEachSystemBroadcast() {
        RescheduleReceiver.ACTIONS.forEach { action ->
            val intent = Intent(action).setPackage(context.packageName)
            val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
            assertTrue(
                receivers.any { it.activityInfo.name == RescheduleReceiver::class.java.name },
                "$action must reach RescheduleReceiver"
            )
        }
    }

    @Test
    fun neitherReceiverIsExportedAndTheAlarmOneHasNoIntentFilter() {
        val manager = context.packageManager
        listOf(DigestAlarmReceiver::class.java, RescheduleReceiver::class.java).forEach {
            val info = manager.getReceiverInfo(ComponentName(context, it), PackageManager.GET_META_DATA)
            assertFalse(info.exported, "${it.simpleName} must not be exported")
            assertTrue(info.enabled)
        }
        val alarm = manager.queryBroadcastReceivers(Intent("android.intent.action.BOOT_COMPLETED"), 0)
        assertFalse(alarm.any { it.activityInfo.name == DigestAlarmReceiver::class.java.name })
    }
}
