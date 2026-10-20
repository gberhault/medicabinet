package com.medicabinet.app.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private fun application(): Application = RuntimeEnvironment.getApplication()

private fun manager(): NotificationManager = application().getSystemService(NotificationManager::class.java)

/** Android 13+: the `POST_NOTIFICATIONS` runtime permission decides, then the app switch and the channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemNotificationAccessApi33Test {
    private val access = SystemNotificationAccess(application())

    @Test
    fun withoutThePermissionTheSystemPromptIsNeededAndNothingCanBePosted() {
        shadowOf(application()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(access.needsRuntimePermission())
        assertFalse(access.canPost())
    }

    @Test
    fun withThePermissionNotificationsCanBePostedAndNoPromptIsNeeded() {
        shadowOf(application()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(access.needsRuntimePermission())
        assertTrue(access.canPost())
    }

    @Test
    fun aGrantedPermissionWithTheAppSwitchOffCannotPostAndHasNoPromptEither() {
        shadowOf(application()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager()).setNotificationsEnabled(false)
        assertFalse(access.needsRuntimePermission())
        assertFalse(access.canPost())
    }

    @Test
    fun aGrantedPermissionWithTheChannelSwitchedOffCannotPost() {
        shadowOf(application()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager().deleteNotificationChannel(ReminderChannel.ID)
        manager().createNotificationChannel(
            NotificationChannel(ReminderChannel.ID, "Monthly reminder", NotificationManager.IMPORTANCE_NONE)
        )
        assertFalse(access.canPost())
    }
}

/** Android 8–12: no permission exists; the app switch and the channel are all that can stop a notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SystemNotificationAccessApi30Test {
    private val access = SystemNotificationAccess(application())

    @Test
    fun noRuntimePermissionIsEverNeededAndNotificationsCanBePosted() {
        assertFalse(access.needsRuntimePermission())
        assertTrue(access.canPost())
    }

    @Test
    fun theAppSwitchOffBlocksThem() {
        shadowOf(manager()).setNotificationsEnabled(false)
        assertFalse(access.needsRuntimePermission())
        assertFalse(access.canPost())
    }

    @Test
    fun theChannelSwitchedOffBlocksThem() {
        manager().deleteNotificationChannel(ReminderChannel.ID)
        manager().createNotificationChannel(
            NotificationChannel(ReminderChannel.ID, "Monthly reminder", NotificationManager.IMPORTANCE_NONE)
        )
        assertFalse(access.canPost())
    }

    @Test
    fun theChannelIsCreatedAtApplicationStart() {
        val channel = assertNotNull(manager().getNotificationChannel("expiry_reminders"))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }
}
