import Foundation
import MediCabinetCore
import Testing
import UserNotifications
@testable import MediCabinet

@Suite struct NotificationClientTests {
    private func day(_ year: Int, _ month: Int) throws -> CalendarDay {
        try CalendarDay.make(year: year, month: month, day: 1)
    }

    // MARK: Ids

    @Test func theIdOfADigestIsItsYearAndMonth() throws {
        #expect(NotificationIDs.digest(for: try day(2027, 4)) == "expiry-digest-2027-04")
        #expect(NotificationIDs.digest(for: try day(2027, 12)) == "expiry-digest-2027-12")
        #expect(NotificationIDs.digest(for: try day(2030, 1)).hasPrefix(NotificationIDs.digestPrefix))
    }

    // MARK: The in-memory client

    @Test func theInMemoryClientAnswersThePromptOnceAndRemembersIt() async throws {
        let client = InMemoryNotificationClient(status: .notDetermined, grantsOnRequest: false)
        #expect(await client.authorization() == .notDetermined)
        #expect(try await client.requestAuthorization() == false)
        #expect(await client.authorization() == .denied)
        #expect(try await client.requestAuthorization() == false, "the system does not ask a second time")
        #expect(await client.requestCount == 2)
    }

    @Test func theInMemoryClientKeepsOnePendingRequestPerIdAndOnlyDigestIDsAreListed() async throws {
        let client = InMemoryNotificationClient(status: .authorized)
        let april = try day(2027, 4)
        let id = NotificationIDs.digest(for: april)
        try await client.schedule(DigestNotification(id: id, day: april, title: "t", body: "one"))
        try await client.schedule(DigestNotification(id: id, day: april, title: "t", body: "two"))
        #expect(await client.pendingList.map(\.body) == ["two"], "same id replaces the pending request")
        #expect(await client.scheduledHistory.count == 2)
        #expect(await client.pendingDigestIDs() == [id])
        await client.removePending(ids: [id])
        #expect(await client.pendingDigestIDs().isEmpty)
    }

    @Test func schedulingCanBeMadeToFail() async throws {
        let client = InMemoryNotificationClient(status: .authorized)
        await client.failScheduling()
        let april = try day(2027, 4)
        await #expect(throws: InMemoryNotificationClient.Failure.self) {
            try await client.schedule(DigestNotification(id: "expiry-digest-2027-04", day: april, title: "t", body: "b"))
        }
        await client.failScheduling(false)
        try await client.schedule(DigestNotification(id: "expiry-digest-2027-04", day: april, title: "t", body: "b"))
        #expect(await client.pendingList.count == 1)
    }

    // MARK: The live client (the system's notification center of the test host)

    @Test func theLiveClientReadsThePermissionWithoutAskingForIt() async {
        let status = await LiveNotificationClient().authorization()
        #expect([NotificationAuthorization.notDetermined, .denied, .authorized].contains(status))
    }

    @Test func aDigestRequestIsAFloatingOneShotTriggerAtNineOnTheDigestDay() throws {
        let december = try day(2099, 12)
        let id = NotificationIDs.digest(for: december)
        let request = LiveNotificationClient.request(
            for: DigestNotification(id: id, day: december, title: "Title", body: "Body"))
        #expect(request.identifier == id)
        let trigger = try #require(request.trigger as? UNCalendarNotificationTrigger)
        #expect(!trigger.repeats)
        #expect(trigger.dateComponents.year == 2099)
        #expect(trigger.dateComponents.month == 12)
        #expect(trigger.dateComponents.day == 1)
        #expect(trigger.dateComponents.hour == 9)
        #expect(trigger.dateComponents.minute == 0)
        #expect(trigger.dateComponents.timeZone == nil, "floating: still 09:00 local after travelling")
        #expect(request.content.title == "Title")
        #expect(request.content.body == "Body")
        #expect(request.content.badge == nil, "no app-icon badge")
        #expect(request.content.sound == .default)
    }

    @Test func theLiveClientSchedulesAndRemovesWithoutFailing() async throws {
        let client = LiveNotificationClient()
        let december = try day(2099, 12)
        let id = NotificationIDs.digest(for: december)
        defer { UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id]) }
        try await client.schedule(DigestNotification(id: id, day: december, title: "Title", body: "Body"))
        // The center keeps the request only when the host app is allowed to notify.
        if await client.authorization() == .authorized {
            #expect(await client.pendingDigestIDs().contains(id))
        }
        await client.removePending(ids: [id])
        #expect(!(await client.pendingDigestIDs().contains(id)))
    }

    @Test func theLiveClientListsOnlyDigestRequests() async throws {
        let center = UNUserNotificationCenter.current()
        let other = UNNotificationRequest(
            identifier: "something-else-\(UUID().uuidString)", content: UNMutableNotificationContent(),
            trigger: UNCalendarNotificationTrigger(dateMatching: DateComponents(year: 2099, month: 1, day: 1), repeats: false))
        try await center.add(other)
        defer { center.removePendingNotificationRequests(withIdentifiers: [other.identifier]) }
        #expect(!(await LiveNotificationClient().pendingDigestIDs().contains(other.identifier)))
        #expect((await LiveNotificationClient().pendingDigestIDs()).allSatisfy { $0.hasPrefix(NotificationIDs.digestPrefix) })
    }

    // MARK: Launch hook for UI tests

    @Test func theUITestHookReplacesTheSystemCenterOnlyWithTemporaryStorage() async throws {
        let withoutStorage = AppLaunch.notificationClient(arguments: ["-UITestNotifications", "denied"])
        #expect(withoutStorage is LiveNotificationClient, "a real launch never reads the test argument")

        func status(_ value: String) async -> NotificationAuthorization {
            let client = AppLaunch.notificationClient(
                arguments: [AppLaunch.temporaryStorageArgument, "-UITestNotifications", value])
            #expect(client is InMemoryNotificationClient)
            return await client.authorization()
        }
        #expect(await status("denied") == .denied)
        #expect(await status("authorized") == .authorized)
        #expect(await status("notDetermined") == .notDetermined)
        #expect(await status("refuseOnPrompt") == .notDetermined)
        let refusing = AppLaunch.notificationClient(
            arguments: [AppLaunch.temporaryStorageArgument, "-UITestNotifications", "refuseOnPrompt"])
        #expect(try await refusing.requestAuthorization() == false)
    }

    @Test func withoutTheArgumentTheLiveCenterIsUsed() {
        #expect(AppLaunch.notificationClient(arguments: [AppLaunch.temporaryStorageArgument]) is LiveNotificationClient)
        #expect(AppLaunch.notificationClient(arguments: [AppLaunch.temporaryStorageArgument, "-UITestNotifications"])
            is LiveNotificationClient)
    }
}
