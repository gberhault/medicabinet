package com.medicabinet.app.reminders

import android.app.NotificationManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private fun channel() = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)
    .getNotificationChannel(ReminderChannel.ID)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class ReminderChannelTest {
    @Test
    fun theChannelHasItsIdAnEnglishNameAndDescriptionAndDefaultImportance() {
        ReminderChannel.ensure(RuntimeEnvironment.getApplication())
        val channel = assertNotNull(channel())
        assertEquals("expiry_reminders", channel.id)
        assertEquals("Monthly reminder", channel.name.toString())
        assertEquals("Monthly summary of expired or soon-to-expire boxes", channel.description)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun creatingItAgainIsHarmless() {
        ReminderChannel.ensure(RuntimeEnvironment.getApplication())
        ReminderChannel.ensure(RuntimeEnvironment.getApplication())
        assertEquals("expiry_reminders", assertNotNull(channel()).id)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr")
class ReminderChannelFrenchTest {
    @Test
    fun theChannelSpeaksFrench() {
        ReminderChannel.ensure(RuntimeEnvironment.getApplication())
        val channel = assertNotNull(channel())
        assertEquals("Rappel mensuel", channel.name.toString())
        assertEquals("Récapitulatif mensuel des boîtes périmées ou bientôt périmées", channel.description)
    }
}
