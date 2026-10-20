package com.medicabinet.app.reminders

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun theIntentOpensThisAppsNotificationSettings() {
        val intent = notificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun openingStartsTheSystemScreen() {
        openNotificationSettings(context)
        val started = assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
    }

    @Test
    fun aDeviceWithoutThatScreenLeavesTheUserWhereTheyAre() {
        var attempts = 0
        val broken = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                attempts++
                throw ActivityNotFoundException("none")
            }
        }
        openNotificationSettings(broken)
        assertEquals(1, attempts)
    }
}
