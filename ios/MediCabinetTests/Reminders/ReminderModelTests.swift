import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

@MainActor
@Suite struct ReminderModelTests {
    private final class SettingsOpens {
        var count = 0
    }

    private struct Fixture {
        let preferences: InMemoryExpiryPreferences
        let client: InMemoryNotificationClient
        let opens: SettingsOpens
        let model: ReminderModel
    }

    private func fixture(
        status: NotificationAuthorization = .notDetermined, grants: Bool = true,
        enabled: Bool = false, dismissed: Bool = false
    ) -> Fixture {
        let preferences = InMemoryExpiryPreferences(
            ExpiryPreferencesSnapshot(reminderEnabled: enabled, cardDismissed: dismissed))
        let client = InMemoryNotificationClient(status: status, grantsOnRequest: grants)
        let opens = SettingsOpens()
        let model = ReminderModel(preferences: preferences, client: client, openSystemSettings: { opens.count += 1 })
        return Fixture(preferences: preferences, client: client, opens: opens, model: model)
    }

    // MARK: Off by default (N1)

    @Test func remindersAreOffAndNothingIsRequestedAtLaunch() async {
        let setup = fixture()
        await setup.model.refresh()
        #expect(setup.model.state == .off)
        #expect(await setup.client.requestCount == 0, "the permission is asked only when the user turns reminders on")
        #expect(setup.model.showsCard)
        #expect(!setup.model.needsSystemSettings)
    }

    // MARK: Turning on (N3)

    @Test func turningOnAsksForPermissionOnceAndGrantedMeansOn() async {
        let setup = fixture()
        await setup.model.turnOn()
        #expect(setup.model.state == .on)
        #expect(setup.preferences.reminderEnabled)
        #expect(await setup.client.requestCount == 1)
        #expect(!setup.model.showsCard, "the card goes once reminders are on")
        #expect(!setup.model.needsSystemSettings)
    }

    @Test func refusingThePromptKeepsRemindersOffAndExplainsWhy() async {
        let setup = fixture(grants: false)
        await setup.model.turnOn()
        #expect(setup.model.state == .off)
        #expect(!setup.preferences.reminderEnabled)
        #expect(setup.model.authorization == .denied)
        #expect(setup.model.needsSystemSettings)
        #expect(setup.model.showsCard, "the card stays until Not now, now offering Open Settings")
    }

    @Test func turningOnWhenTheSystemAlreadyRefusesDoesNotAskAgain() async {
        let setup = fixture(status: .denied)
        await setup.model.turnOn()
        #expect(setup.model.state == .off)
        #expect(!setup.preferences.reminderEnabled)
        #expect(await setup.client.requestCount == 0, "the system shows its prompt only once")
        #expect(setup.model.needsSystemSettings)
    }

    @Test func turningOnWhenAlreadyAllowedDoesNotShowThePromptAgain() async {
        let setup = fixture(status: .authorized)
        await setup.model.turnOn()
        #expect(setup.model.state == .on)
        #expect(await setup.client.requestCount == 0)
    }

    @Test func aPromptThatFailsLeavesRemindersOffAndCanBeTriedAgain() async {
        let setup = fixture()
        await setup.client.failAuthorizationRequests()
        await setup.model.turnOn()
        #expect(setup.model.state == .off)
        #expect(setup.model.requestFailed)
        #expect(!setup.preferences.reminderEnabled)

        await setup.client.failAuthorizationRequests(false)
        await setup.model.turnOn()
        #expect(setup.model.state == .on)
        #expect(!setup.model.requestFailed)
    }

    // MARK: Reading the state back (N4)

    @Test func theStoredChoiceAndTheSystemPermissionGiveTheState() async {
        let on = fixture(status: .authorized, enabled: true)
        await on.model.refresh()
        #expect(on.model.state == .on)

        let off = fixture(status: .authorized, enabled: false)
        await off.model.refresh()
        #expect(off.model.state == .off)
    }

    @Test func aPermissionRevokedInTheSystemSettingsShowsBlockedNotOn() async {
        let setup = fixture(status: .authorized, enabled: true)
        await setup.model.refresh()
        #expect(setup.model.state == .on)

        await setup.client.setStatus(.denied)
        await setup.model.refresh()
        #expect(setup.model.state == .blocked)
        #expect(setup.model.needsSystemSettings)
        #expect(setup.preferences.reminderEnabled, "the choice is kept, so it resumes when the permission comes back")
        #expect(!setup.model.showsCard, "blocked is not 'never asked': no card")
    }

    @Test func blockedReturnsToOnWhenThePermissionIsGrantedAgain() async {
        let setup = fixture(status: .denied, enabled: true)
        await setup.model.refresh()
        #expect(setup.model.state == .blocked)
        await setup.client.setStatus(.authorized)
        await setup.model.refresh()
        #expect(setup.model.state == .on)
    }

    @Test func aChoiceThatWasNeverGrantedCannotBeOn() async {
        let setup = fixture(status: .notDetermined, enabled: true)
        await setup.model.refresh()
        #expect(setup.model.state == .off)
        #expect(!setup.preferences.reminderEnabled)
    }

    // MARK: Turning off (N13)

    @Test func turningOffCancelsEveryPendingDigest() async throws {
        let setup = fixture(status: .authorized, enabled: true)
        let day = try CalendarDay.make(year: 2027, month: 4, day: 1)
        try await setup.client.schedule(DigestNotification(id: NotificationIDs.digest(for: day), day: day, title: "t", body: "b"))
        await setup.model.refresh()
        #expect(await setup.client.pendingDigestIDs().count == 1)

        await setup.model.turnOff()
        #expect(setup.model.state == .off)
        #expect(!setup.preferences.reminderEnabled)
        #expect(await setup.client.pendingDigestIDs().isEmpty)
    }

    @Test func turnedOffThenOnAgainAsksNothingMore() async {
        let setup = fixture()
        await setup.model.turnOn()
        await setup.model.turnOff()
        await setup.model.turnOn()
        #expect(setup.model.state == .on)
        #expect(await setup.client.requestCount == 1)
    }

    // MARK: The card (N2)

    @Test func notNowHidesTheCardForGoodAndItIsRemembered() async {
        let setup = fixture()
        #expect(setup.model.showsCard)
        setup.model.dismissCard()
        #expect(!setup.model.showsCard)
        #expect(setup.preferences.cardDismissed)

        let relaunched = ReminderModel(preferences: setup.preferences, client: setup.client, openSystemSettings: {})
        #expect(!relaunched.showsCard, "remembered across launches")
        await relaunched.refresh()
        #expect(!relaunched.showsCard)
    }

    @Test func theCardIsHiddenWhileRemindersAreOn() async {
        let setup = fixture(status: .authorized, enabled: true)
        await setup.model.refresh()
        #expect(!setup.model.showsCard)
    }

    // MARK: Open Settings

    @Test func openSettingsGoesToTheSystemSettings() {
        let setup = fixture(status: .denied)
        setup.model.openSettings()
        #expect(setup.opens.count == 1)
    }
}
