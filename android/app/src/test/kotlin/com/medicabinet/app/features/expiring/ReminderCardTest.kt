package com.medicabinet.app.features.expiring

import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.medicabinet.app.reminders.FakeNotificationAccess
import com.medicabinet.app.reminders.ReminderState
import com.medicabinet.app.reminders.ReminderTestKit
import com.medicabinet.app.ui.theme.MediCabinetTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Stands in for the system permission prompt: records what is asked and answers like the user would. */
class AnsweringRegistry(private val kit: ReminderTestKit, private val allow: Boolean) : ActivityResultRegistry() {
    val prompts = mutableListOf<String>()

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?
    ) {
        prompts += input as String
        if (allow) kit.access.grant()
        dispatchResult(requestCode, allow)
    }

    fun owner(): ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = this@AnsweringRegistry
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w360dp-h2400dp")
class ReminderCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(kit: ReminderTestKit, registry: AnsweringRegistry) = kit.model().also { model ->
        compose.setContent {
            MediCabinetTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner()) {
                    ReminderCard(model, rememberReminderActions(model))
                }
            }
        }
    }

    @Test
    fun offersTheMonthlyReminderAndNeverPromptsByItself() {
        val kit = ReminderTestKit()
        val registry = AnsweringRegistry(kit, allow = true)
        show(kit, registry)
        compose.onNodeWithText("Monthly reminder").assertIsDisplayed()
        compose.onNodeWithText("Get a summary on the 1st of each month when boxes are expired or expiring soon.")
            .assertIsDisplayed()
        compose.onNodeWithTag("reminder.card.turnOn").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("reminder.card.notNow").assertIsDisplayed().assertHasClickAction()
        assertTrue(registry.prompts.isEmpty())
    }

    @Test
    fun turnOnAsksForTheNotificationPermissionAndGrantingTurnsTheReminderOnAndHidesTheCard() {
        val kit = ReminderTestKit()
        val registry = AnsweringRegistry(kit, allow = true)
        val model = show(kit, registry)
        compose.onNodeWithTag("reminder.card.turnOn").performClick()
        compose.waitForIdle()
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), registry.prompts)
        assertEquals(ReminderState.ON, model.state.value)
        assertEquals(1, kit.scheduling.arms)
        assertEquals(0, compose.onAllCardNodes())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllCardNodes() =
        onAllNodesWithTag("reminder.card").fetchSemanticsNodes().size

    @Test
    fun refusingThePermissionSaysWhyOffersOpenSettingsAndKeepsTheReminderOff() {
        val kit = ReminderTestKit()
        val registry = AnsweringRegistry(kit, allow = false)
        val model = show(kit, registry)
        compose.onNodeWithTag("reminder.card.turnOn").performClick()
        compose.waitForIdle()
        assertEquals(ReminderState.OFF, model.state.value)
        assertFalse(kit.preferences.reminderEnabled)
        compose.onNodeWithText("Without notification permission, the reminder can’t be sent.").assertIsDisplayed()
        compose.onNodeWithTag("reminder.card.openSettings").assertHasClickAction().performClick()
        val opened = assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
    }

    @Test
    fun beforeAndroid13SwitchedOffNotificationsAreSaidWithoutAnyPrompt() {
        val kit = ReminderTestKit(FakeNotificationAccess(canPostNow = false, permissionMissing = false))
        val registry = AnsweringRegistry(kit, allow = false)
        show(kit, registry)
        compose.onNodeWithTag("reminder.card.turnOn").performClick()
        compose.waitForIdle()
        assertTrue(registry.prompts.isEmpty())
        compose.onNodeWithText("Notifications are turned off for MediCabinet.").assertIsDisplayed()
        compose.onNodeWithTag("reminder.card.openSettings").assertIsDisplayed()
    }

    @Test
    fun notNowHidesTheCardForGood() {
        val kit = ReminderTestKit()
        show(kit, AnsweringRegistry(kit, allow = true))
        compose.onNodeWithTag("reminder.card.notNow").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllCardNodes())
        assertTrue(kit.preferences.reminderCardDismissed)
        assertFalse(kit.preferences.reminderEnabled)
    }

    @Test
    fun aDismissedCardStaysHiddenOnTheNextLaunch() {
        val kit = ReminderTestKit()
        kit.preferences.reminderCardDismissed = true
        show(kit, AnsweringRegistry(kit, allow = true))
        assertEquals(0, compose.onAllCardNodes())
    }

    @Test
    fun aRevokedReminderIsShownAsBlockedWithOpenSettingsAndNeverAsOn() {
        val kit = ReminderTestKit(FakeNotificationAccess(canPostNow = false, permissionMissing = true))
        kit.preferences.reminderEnabled = true
        show(kit, AnsweringRegistry(kit, allow = false))
        compose.onNodeWithText("Notifications are turned off for MediCabinet.").assertIsDisplayed()
        compose.onNodeWithTag("reminder.card.openSettings").assertHasClickAction()
        assertEquals(0, compose.onAllNodesWithTag("reminder.card.turnOn").fetchSemanticsNodes().size)
    }

    @Test
    fun staysReachableAtTwiceTheFontSize() {
        RuntimeEnvironment.setFontScale(2f)
        val kit = ReminderTestKit()
        show(kit, AnsweringRegistry(kit, allow = true))
        compose.onNodeWithTag("reminder.card.turnOn").assertIsDisplayed()
        compose.onNodeWithTag("reminder.card.notNow").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr-w360dp-h2400dp")
class ReminderCardFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theCardSpeaksFrench() {
        val kit = ReminderTestKit()
        val model = kit.model()
        compose.setContent { MediCabinetTheme { ReminderCard(model, ReminderActions({}, {})) } }
        compose.onNodeWithText("Rappel mensuel").assertIsDisplayed()
        compose.onNodeWithText("Activer").assertIsDisplayed()
        compose.onNodeWithText("Plus tard").assertIsDisplayed()
    }
}
