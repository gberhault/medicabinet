package com.medicabinet.app.reminders

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.medicabinet.app.MainActivity
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.expiry.DigestCounts
import com.medicabinet.core.expiry.ExpiryWindow
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private val context: Context get() = RuntimeEnvironment.getApplication()

private fun posted(): List<Notification> =
    shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

private fun Notification.text(): String = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class DigestNotifierTest {
    private val notifier = NotificationDigestNotifier(context)

    @Test
    fun expiredOnlyIsSaidWithItsPlural() {
        assertEquals(
            DigestText("Your medicine cabinet", "1 box expired"),
            notifier.text(DigestCounts(expired = 1, expiringSoon = 0), ExpiryWindow.DAYS_60)
        )
        assertEquals(
            "3 boxes expired",
            notifier.text(DigestCounts(expired = 3, expiringSoon = 0), ExpiryWindow.DAYS_60).body
        )
    }

    @Test
    fun expiringSoonOnlyNamesTheWindowInDays() {
        assertEquals(
            "1 box expires within 30 days",
            notifier.text(DigestCounts(expired = 0, expiringSoon = 1), ExpiryWindow.DAYS_30).body
        )
        assertEquals(
            "4 boxes expire within 90 days",
            notifier.text(DigestCounts(expired = 0, expiringSoon = 4), ExpiryWindow.DAYS_90).body
        )
    }

    @Test
    fun bothCountsAreJoinedWithTheMiddleDot() {
        assertEquals(
            "2 boxes expired · 3 boxes expire within 60 days",
            notifier.text(DigestCounts(expired = 2, expiringSoon = 3), ExpiryWindow.DAYS_60).body
        )
    }

    @Test
    fun postingShowsOneNotificationOnTheReminderChannelThatOpensTheApp() {
        notifier.post(DigestCounts(expired = 2, expiringSoon = 0), ExpiryWindow.DAYS_60)
        val notification = posted().single()
        assertEquals("Your medicine cabinet", notification.title())
        assertEquals("2 boxes expired", notification.text())
        assertEquals(ReminderChannel.ID, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun aNewDigestReplacesAnUnreadOne() {
        notifier.post(DigestCounts(expired = 1, expiringSoon = 0), ExpiryWindow.DAYS_60)
        notifier.post(DigestCounts(expired = 2, expiringSoon = 0), ExpiryWindow.DAYS_60)
        assertEquals("2 boxes expired", posted().single().text())
    }

    @Test
    fun tappingOpensTheExpiringTabThroughAnImmutablePendingIntent() {
        notifier.post(DigestCounts(expired = 1, expiringSoon = 0), ExpiryWindow.DAYS_60)
        val tap = assertNotNull(posted().single().contentIntent)
        val shadow = shadowOf(tap)
        assertTrue(shadow.isActivityIntent)
        assertTrue(shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = shadow.savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("expiring", intent.getStringExtra("destination"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    /** The whole path with named, dated, numbered boxes: the notification carries counts and nothing else. */
    @Test
    fun theNotificationNeverContainsABoxNameLotSerialOrDate() {
        val kit = DeliveryKit(Instant.parse("2027-03-20T12:00:00Z"), notifier = notifier)
        kit.box("Doliprane 1000 mg", ExpiryDate.of(2027, 2, null), lot = "LOT-A1B2C3", serial = "SER-98765")
        kit.box("Efferalgan", ExpiryDate.of(2027, 4, 10), lot = "L-ZZ9", serial = "S-4242")
        runBlocking { kit.delivery.deliver() }
        val notification = posted().single()
        assertEquals("1 box expired · 1 box expires within 60 days", notification.text())
        val everything = buildString {
            append(notification.title()).append(' ').append(notification.text()).append(' ')
            append(notification.tickerText).append(' ')
            notification.extras.keySet().forEach { append(notification.extras.get(it)).append(' ') }
        }
        listOf("Doliprane", "Efferalgan", "LOT-", "SER-", "S-4242", "L-ZZ9", "2027", "2026", "mg").forEach {
            assertFalse(everything.contains(it), "the notification must not mention '$it': $everything")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr")
class DigestNotifierFrenchTest {
    private val notifier = NotificationDigestNotifier(context)

    @Test
    fun theDigestSpeaksFrenchWithPluralsAndTheWindow() {
        assertEquals("Votre armoire à pharmacie", notifier.text(DigestCounts(1, 0), ExpiryWindow.DAYS_60).title)
        assertEquals("1 boîte périmée", notifier.text(DigestCounts(1, 0), ExpiryWindow.DAYS_60).body)
        assertEquals("2 boîtes périmées", notifier.text(DigestCounts(2, 0), ExpiryWindow.DAYS_60).body)
        assertEquals("1 boîte périme dans les 30 jours", notifier.text(DigestCounts(0, 1), ExpiryWindow.DAYS_30).body)
        assertEquals(
            "3 boîtes périment dans les 90 jours",
            notifier.text(DigestCounts(0, 3), ExpiryWindow.DAYS_90).body
        )
        assertEquals(
            "2 boîtes périmées · 3 boîtes périment dans les 60 jours",
            notifier.text(DigestCounts(2, 3), ExpiryWindow.DAYS_60).body
        )
    }
}
