package com.medicabinet.app.features.settings

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.medicabinet.app.app.InMemoryExpiryPreferences
import com.medicabinet.app.features.expiring.AnsweringRegistry
import com.medicabinet.app.features.expiring.rememberReminderActions
import com.medicabinet.app.reminders.FakeNotificationAccess
import com.medicabinet.app.reminders.ReminderState
import com.medicabinet.app.reminders.ReminderTestKit
import com.medicabinet.app.ui.theme.MediCabinetTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w360dp-h2400dp")
class ReminderSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(kit: ReminderTestKit, registry: AnsweringRegistry) = kit.model().also { model ->
        compose.setContent {
            MediCabinetTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner()) {
                    ExpirySettingsSection(InMemoryExpiryPreferences(), model, rememberReminderActions(model))
                }
            }
        }
    }

    @Test
    fun theSwitchIsOffByDefaultWithItsFooterAndTheWindowSettingAbove() {
        val kit = ReminderTestKit()
        show(kit, AnsweringRegistry(kit, allow = true))
        compose.onNodeWithText("Expiring soon").assertIsDisplayed()
        compose.onNodeWithText("Monthly reminder").assertIsDisplayed()
        compose.onNodeWithTag("settings.reminder.switch").assertIsOff()
        compose.onNodeWithText(
            "One notification on the 1st of the month at 9:00, with only the number of boxes, never a medicine name."
        ).assertIsDisplayed()
    }

    @Test
    fun turningItOnAsksThePermissionThenIsOn() {
        val kit = ReminderTestKit()
        val registry = AnsweringRegistry(kit, allow = true)
        val model = show(kit, registry)
        compose.onNodeWithTag("settings.reminder.switch").performClick()
        compose.waitForIdle()
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), registry.prompts)
        assertEquals(ReminderState.ON, model.state.value)
        compose.onNodeWithTag("settings.reminder.switch").assertIsOn()
        assertTrue(kit.preferences.reminderEnabled)
    }

    @Test
    fun turningItOffCancelsTheDigest() {
        val kit = ReminderTestKit(FakeNotificationAccess(canPostNow = true, permissionMissing = false))
        val model = show(kit, AnsweringRegistry(kit, allow = true))
        model.turnOn()
        compose.waitForIdle()
        compose.onNodeWithTag("settings.reminder.switch").assertIsOn().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings.reminder.switch").assertIsOff()
        assertFalse(kit.preferences.reminderEnabled)
        // On, then off: the scheduler was asked each time (the second time it cancels).
        assertEquals(2, kit.scheduling.arms)
    }

    @Test
    fun aRefusedPromptLeavesTheSwitchOffAndExplains() {
        val kit = ReminderTestKit()
        show(kit, AnsweringRegistry(kit, allow = false))
        compose.onNodeWithTag("settings.reminder.switch").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings.reminder.switch").assertIsOff()
        compose.onNodeWithText("Without notification permission, the reminder can’t be sent.").assertIsDisplayed()
        compose.onNodeWithTag("settings.reminder.openSettings").assertIsDisplayed()
    }

    @Test
    fun aRevokedPermissionIsNotShownAsOnAndSaysSo() {
        val kit = ReminderTestKit(FakeNotificationAccess(canPostNow = false, permissionMissing = true))
        kit.preferences.reminderEnabled = true
        show(kit, AnsweringRegistry(kit, allow = false))
        compose.onNodeWithTag("settings.reminder.switch").assertIsOff()
        compose.onNodeWithText("Notifications are turned off for MediCabinet.").assertIsDisplayed()
        compose.onNodeWithTag("settings.reminder.openSettings").assertIsDisplayed()
    }

    @Test
    fun staysReachableAtTwiceTheFontSize() {
        RuntimeEnvironment.setFontScale(2f)
        val kit = ReminderTestKit()
        show(kit, AnsweringRegistry(kit, allow = true))
        compose.onNodeWithTag("settings.reminder.switch").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr-w360dp-h2400dp")
class ReminderSettingFrenchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSettingSpeaksFrench() {
        val kit = ReminderTestKit()
        val model = kit.model()
        compose.setContent {
            MediCabinetTheme {
                ExpirySettingsSection(
                    InMemoryExpiryPreferences(),
                    model,
                    com.medicabinet.app.features.expiring.ReminderActions({}, {})
                )
            }
        }
        compose.onNodeWithText("Rappel mensuel").assertIsDisplayed()
        compose.onNodeWithText("Bientôt périmé").assertIsDisplayed()
    }
}
