package com.medicabinet.app.reminders

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ReminderModelTest {
    @Test
    fun remindersAreOffByDefaultWithTheCardOfferedAndNothingScheduled() {
        val kit = ReminderTestKit()
        val model = kit.model()
        assertEquals(ReminderState.OFF, model.state.value)
        assertEquals(ReminderNotice.NONE, model.notice.value)
        assertTrue(model.offersCard.value)
        assertEquals(0, kit.scheduling.arms)
    }

    @Test
    fun turningOnWithTheSystemAllowingEnablesAndArms() {
        val kit = ReminderTestKit(FakeNotificationAccess(canPostNow = true, permissionMissing = false))
        val model = kit.model()
        assertEquals(TurnOnResult.ENABLED, model.turnOn())
        assertEquals(ReminderState.ON, model.state.value)
        assertTrue(kit.preferences.reminderEnabled)
        assertEquals(1, kit.scheduling.arms)
        assertFalse(model.offersCard.value)
    }

    @Test
    fun turningOnMidMonthDoesNotOweThisMonthsDigest() {
        val kit = ReminderTestKit(FakeNotificationAccess(canPostNow = true, permissionMissing = false))
        kit.model().turnOn()
        // 2027-01-15: the digest due is January's, already "handled", so only the 1st of February comes.
        assertEquals("2027-01", kit.preferences.lastDigestMonth)
    }

    @Test
    fun turningOnOnTheFirstBeforeNineOwesNothingYetEither() {
        val kit = ReminderTestKit(
            FakeNotificationAccess(canPostNow = true, permissionMissing = false),
            Instant.parse("2027-02-01T08:00:00Z")
        )
        kit.model().turnOn()
        // The 1st at 09:00 is still to come: the last digest day is January's.
        assertEquals("2027-01", kit.preferences.lastDigestMonth)
    }

    @Test
    fun theSystemPromptIsOnlyAskedForWhenTheUserTurnsItOnAndThePermissionIsMissing() {
        val kit = ReminderTestKit()
        val model = kit.model()
        assertEquals(TurnOnResult.NEEDS_PERMISSION, model.turnOn())
        // Nothing is on until the answer comes.
        assertEquals(ReminderState.OFF, model.state.value)
        assertFalse(kit.preferences.reminderEnabled)
        assertEquals(0, kit.scheduling.arms)
    }

    @Test
    fun grantingThePermissionTurnsTheReminderOn() {
        val kit = ReminderTestKit()
        val model = kit.model()
        model.turnOn()
        kit.access.grant()
        model.onPermissionResult(granted = true)
        assertEquals(ReminderState.ON, model.state.value)
        assertEquals(ReminderNotice.NONE, model.notice.value)
        assertEquals(1, kit.scheduling.arms)
    }

    @Test
    fun refusingThePermissionKeepsItOffAndSaysWhy() {
        val kit = ReminderTestKit()
        val model = kit.model()
        model.turnOn()
        model.onPermissionResult(granted = false)
        assertEquals(ReminderState.OFF, model.state.value)
        assertEquals(ReminderNotice.DENIED, model.notice.value)
        assertFalse(kit.preferences.reminderEnabled)
        assertEquals(0, kit.scheduling.arms)
        // The card is still offered; the user may try again.
        assertTrue(model.offersCard.value)
    }

    @Test
    fun aNewAttemptClearsTheExplanation() {
        val kit = ReminderTestKit()
        val model = kit.model()
        model.turnOn()
        model.onPermissionResult(granted = false)
        model.turnOn()
        assertEquals(ReminderNotice.NONE, model.notice.value)
    }

    @Test
    fun aGrantedPermissionWithTheChannelBlockedIsBlockedNotOn() {
        val kit = ReminderTestKit()
        val model = kit.model()
        model.turnOn()
        // Granted, yet the system still refuses (the channel or the app switch is off).
        model.onPermissionResult(granted = true)
        assertEquals(ReminderState.OFF, model.state.value)
        assertEquals(ReminderNotice.BLOCKED, model.notice.value)
        assertFalse(kit.preferences.reminderEnabled)
    }

    @Test
    fun beforeAndroid13NotificationsSwitchedOffAreSaidWithoutAPrompt() {
        val kit = ReminderTestKit(FakeNotificationAccess(canPostNow = false, permissionMissing = false))
        val model = kit.model()
        assertEquals(TurnOnResult.BLOCKED, model.turnOn())
        assertEquals(ReminderState.OFF, model.state.value)
        assertEquals(ReminderNotice.BLOCKED, model.notice.value)
        assertEquals(0, kit.scheduling.arms)
    }

    @Test
    fun aPermissionRevokedLaterShowsAsBlockedAtTheNextForegroundNeverAsOn() {
        val access = FakeNotificationAccess(canPostNow = true, permissionMissing = false)
        val kit = ReminderTestKit(access)
        val model = kit.model()
        model.turnOn()
        assertEquals(ReminderState.ON, model.state.value)
        access.canPostNow = false
        access.permissionMissing = true
        model.refresh()
        assertEquals(ReminderState.BLOCKED, model.state.value)
        assertEquals(ReminderNotice.BLOCKED, model.notice.value)
        // The user's choice is remembered: allowing notifications again brings it back by itself.
        assertTrue(kit.preferences.reminderEnabled)
        access.grant()
        model.refresh()
        assertEquals(ReminderState.ON, model.state.value)
        assertEquals(ReminderNotice.NONE, model.notice.value)
    }

    @Test
    fun aRevokedReminderStartsBlockedInANewProcess() {
        val kit = ReminderTestKit(FakeNotificationAccess(canPostNow = false, permissionMissing = true))
        kit.preferences.reminderEnabled = true
        val model = kit.model()
        assertEquals(ReminderState.BLOCKED, model.state.value)
        assertFalse(model.offersCard.value)
    }

    @Test
    fun turningOffCancelsEverythingAndIsRemembered() {
        val kit = ReminderTestKit(FakeNotificationAccess(canPostNow = true, permissionMissing = false))
        val model = kit.model()
        model.turnOn()
        val armsBefore = kit.scheduling.arms
        model.turnOff()
        assertEquals(ReminderState.OFF, model.state.value)
        assertFalse(kit.preferences.reminderEnabled)
        // The scheduler is asked again: it sees the reminder off and cancels the alarm.
        assertEquals(armsBefore + 1, kit.scheduling.arms)
        assertEquals(ReminderNotice.NONE, model.notice.value)
    }

    @Test
    fun notNowHidesTheCardForGoodAcrossLaunches() {
        val kit = ReminderTestKit()
        val model = kit.model()
        model.dismissCard()
        assertFalse(model.offersCard.value)
        assertTrue(kit.preferences.reminderCardDismissed)
        // A new model (a new launch) on the same preferences does not offer it again.
        assertFalse(kit.model().offersCard.value)
        // Dismissing is not turning on: nothing is scheduled.
        assertEquals(0, kit.scheduling.arms)
        assertNull(kit.preferences.lastDigestMonth)
    }

    @Test
    fun theCardIsNotOfferedWhileTheReminderIsOn() {
        val kit = ReminderTestKit(FakeNotificationAccess(canPostNow = true, permissionMissing = false))
        val model = kit.model()
        model.turnOn()
        assertFalse(model.offersCard.value)
        model.turnOff()
        // Off again and never dismissed: offered again.
        assertTrue(model.offersCard.value)
    }
}
