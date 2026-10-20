import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

/// A sleep the test opens by hand: every sleeper waits until `releaseAll()`.
private actor DebounceGate {
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private(set) var requested: [Duration] = []

    func sleep(_ duration: Duration) async {
        requested.append(duration)
        await withCheckedContinuation { waiters.append($0) }
    }

    func releaseAll() {
        let current = waiters
        waiters = []
        for waiter in current { waiter.resume() }
    }
}

private final class ZoneBox: @unchecked Sendable {
    private let lock = NSLock()
    private var zone: TimeZone

    init(_ zone: TimeZone) { self.zone = zone }

    var value: TimeZone {
        get { lock.withLock { zone } }
        set { lock.withLock { zone = newValue } }
    }
}

@MainActor
@Suite struct ReminderSchedulerTests {
    private struct Setup {
        let kit: CabinetTestKit
        let preferences: InMemoryExpiryPreferences
        let client: InMemoryNotificationClient
        let scheduler: ReminderScheduler
        let zone: ZoneBox
    }

    private func english() throws -> Bundle {
        let path = try #require(Bundle.main.path(forResource: "en", ofType: "lproj"))
        return try #require(Bundle(path: path))
    }

    private func setup(
        enabled: Bool = true, status: NotificationAuthorization = .authorized, window: ExpiryWindow = .days60,
        todaySource: TodaySource = .never,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { _ in }
    ) async throws -> Setup {
        let kit = try await CabinetTestKit()
        let preferences = InMemoryExpiryPreferences(ExpiryPreferencesSnapshot(window: window, reminderEnabled: enabled))
        let client = InMemoryNotificationClient(status: status)
        let zone = ZoneBox(.gmt)
        let scheduler = ReminderScheduler(
            client: client, boxes: kit.boxes, preferences: preferences, clock: kit.clock,
            timeZone: { zone.value }, todaySource: todaySource, sleep: sleep,
            bundle: try english(), locale: Locale(identifier: "en_US"))
        return Setup(kit: kit, preferences: preferences, client: client, scheduler: scheduler, zone: zone)
    }

    private func ids(_ client: InMemoryNotificationClient) async -> [String] {
        await client.pendingDigestIDs()
    }

    /// A box with an expiry on an exact day.
    @discardableResult
    private func add(_ kit: CabinetTestKit, _ name: String, _ year: Int, _ month: Int, _ day: Int?) async throws -> Box {
        let box = Box(
            product: ProductRef(gtin: nil, name: name), category: .uncategorized, locationID: kit.home.id,
            expiry: try ExpiryDate.make(year: year, month: month, day: day), createdAt: kit.clock.now)
        try await kit.boxes.insert(box)
        return box
    }

    // MARK: What is scheduled (N5, N7, N11)

    @Test func oneDigestPerMonthWithItsOwnCountsAndEmptyMonthsSkipped() async throws {
        let rig = try await setup()
        // Today is 2027-01-15. Expires 5 April: 63 days left on 1 Feb (outside 60), then soon, then expired.
        try await add(rig.kit, "Box", 2027, 4, 5)
        await rig.scheduler.reschedule()

        let pending = await rig.client.pendingList
        #expect(pending.map(\.id) == [
            "expiry-digest-2027-03", "expiry-digest-2027-04", "expiry-digest-2027-05", "expiry-digest-2027-06",
            "expiry-digest-2027-07", "expiry-digest-2027-08", "expiry-digest-2027-09", "expiry-digest-2027-10",
            "expiry-digest-2027-11", "expiry-digest-2027-12", "expiry-digest-2028-01"
        ], "February has nothing to report, so it has no digest")
        #expect(pending.first?.body == "1 box expires within 60 days")
        #expect(pending[1].body == "1 box expires within 60 days", "1 April: four days left")
        #expect(pending[2].body == "1 box expired")
        #expect(pending.allSatisfy { $0.title == "Your medicine cabinet" })
        #expect(pending.allSatisfy { $0.day.day == 1 })
    }

    @Test func aBoxExpiringExactlyOnTheDigestDayIsStillExpiringSoon() async throws {
        let rig = try await setup()
        try await add(rig.kit, "Box", 2027, 3, 1)
        await rig.scheduler.reschedule()
        let pending = await rig.client.pendingList
        #expect(pending.first { $0.id == "expiry-digest-2027-03" }?.body == "1 box expires within 60 days")
        #expect(pending.first { $0.id == "expiry-digest-2027-04" }?.body == "1 box expired")
    }

    @Test func bothCountsAreJoinedAndPluralsFollowTheCount() async throws {
        let rig = try await setup()
        try await add(rig.kit, "Old one", 2026, 12, 1)
        try await add(rig.kit, "Old two", 2026, 11, 1)
        try await add(rig.kit, "Soon", 2027, 2, 20)
        await rig.scheduler.reschedule()
        let february = try #require(await rig.client.pendingList.first { $0.id == "expiry-digest-2027-02" })
        #expect(february.body == "2 boxes expired · 1 box expires within 60 days")
    }

    @Test func monthsWithOnlyUnknownExpiriesGetNoDigest() async throws {
        let rig = try await setup()
        try await rig.kit.add("Thermometer", gtin: nil)
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).isEmpty)
    }

    @Test func neverMoreThanTwelveArePendingEvenAfterManyReschedules() async throws {
        let rig = try await setup()
        try await add(rig.kit, "Old", 2020, 1, 1)
        for _ in 0..<3 { await rig.scheduler.reschedule() }
        #expect(await ids(rig.client).count == 12)
        #expect(ExpiryDigest.scheduledAhead == 12)
        #expect(await ids(rig.client).first == "expiry-digest-2027-02")
        #expect(await ids(rig.client).last == "expiry-digest-2028-01")
    }

    @Test func oldDigestsThatNoLongerApplyAreRemoved() async throws {
        let rig = try await setup()
        let box = try await add(rig.kit, "Box", 2027, 4, 5)
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).contains("expiry-digest-2027-03"))

        try await rig.kit.boxes.markRemoved(id: box.id, reason: .usedUp, at: rig.kit.clock.now)
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).isEmpty, "a removed box is counted nowhere")
    }

    // MARK: What changes it (N11)

    @Test func theWindowChangesWhichMonthsHaveADigest() async throws {
        let rig = try await setup(window: .days30)
        try await add(rig.kit, "Box", 2027, 4, 20)  // 50 days left on 1 March, 19 on 1 April
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).first == "expiry-digest-2027-04")

        rig.preferences.window = .days60
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).first == "expiry-digest-2027-03")
        let march = try #require(await rig.client.pendingList.first)
        #expect(march.body == "1 box expires within 60 days")
    }

    @Test func aTimeZoneChangeMovesTheFirstDigest() async throws {
        let rig = try await setup()
        try await add(rig.kit, "Old", 2020, 1, 1)
        rig.kit.clock.advance(by: 16 * 86_400 + 15 * 3_600 + 1_800)  // 2027-01-31 23:30 UTC
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).first == "expiry-digest-2027-02")

        // Ten hours ahead it is already 1 February 09:30: that month's digest time has passed.
        rig.zone.value = TimeZone(secondsFromGMT: 10 * 3_600) ?? .gmt
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).first == "expiry-digest-2027-03")
        #expect(await ids(rig.client).count == 12)
    }

    @Test func thePendingDigestsFollowTheCabinetWhileRunning() async throws {
        let rig = try await setup()
        let running = Task { await rig.scheduler.run() }
        defer { running.cancel() }
        #expect(await eventuallyAsync { await rig.scheduler.rescheduleCount >= 1 })
        #expect(await rig.client.pendingDigestIDs().isEmpty)

        try await add(rig.kit, "Old", 2020, 1, 1)
        #expect(await eventuallyAsync { await rig.client.pendingDigestIDs().count == 12 })
    }

    @Test func turningTheReminderOffWhileRunningRemovesEverything() async throws {
        let rig = try await setup()
        try await add(rig.kit, "Old", 2020, 1, 1)
        let running = Task { await rig.scheduler.run() }
        defer { running.cancel() }
        #expect(await eventuallyAsync { await rig.client.pendingDigestIDs().count == 12 })

        rig.preferences.reminderEnabled = false
        #expect(await eventuallyAsync { await rig.client.pendingDigestIDs().isEmpty })
    }

    @Test func aDayChangeWhileRunningWritesTheDigestsAgain() async throws {
        let (signals, signal) = AsyncStream<Void>.makeStream()
        let rig = try await setup(todaySource: TodaySource { signals })
        try await add(rig.kit, "Old", 2020, 1, 1)
        let running = Task { await rig.scheduler.run() }
        defer { running.cancel() }
        #expect(await eventuallyAsync { await rig.client.pendingDigestIDs().first == "expiry-digest-2027-02" })

        rig.kit.clock.advance(by: 20 * 86_400)  // 4 February: the February digest has gone by
        signal.yield()
        #expect(await eventuallyAsync { await rig.client.pendingDigestIDs().first == "expiry-digest-2027-03" })
    }

    // MARK: Debounce

    @Test func manyChangesInARowAreOneRescheduleAfterTheDebounce() async throws {
        let gate = DebounceGate()
        let rig = try await setup(sleep: { await gate.sleep($0) })
        try await add(rig.kit, "Old", 2020, 1, 1)
        for _ in 0..<5 { await rig.scheduler.requestReschedule() }
        #expect(await eventuallyAsync { await gate.requested.count == 5 })
        #expect(await gate.requested.allSatisfy { $0 == ReminderScheduler.debounce })
        #expect(ReminderScheduler.debounce == .seconds(1))
        #expect(await rig.scheduler.rescheduleCount == 0, "nothing is written during the wait")

        await gate.releaseAll()
        #expect(await eventuallyAsync { await rig.scheduler.rescheduleCount == 1 })
        try await Task.sleep(for: .milliseconds(100))
        #expect(await rig.scheduler.rescheduleCount == 1, "the earlier requests were replaced by the last one")
        #expect(await ids(rig.client).count == 12)
    }

    @Test func overlappingReschedulesAreFoldedIntoConsistentPasses() async throws {
        let rig = try await setup()
        try await add(rig.kit, "Old", 2020, 1, 1)
        async let first: Void = rig.scheduler.reschedule()
        async let second: Void = rig.scheduler.reschedule()
        _ = await (first, second)
        #expect(await ids(rig.client).count == 12)
    }

    // MARK: Permission and setting (N3, N4, N13)

    @Test func withoutPermissionEveryPendingDigestIsRemoved() async throws {
        let rig = try await setup(status: .authorized)
        try await add(rig.kit, "Old", 2020, 1, 1)
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).count == 12)

        await rig.client.setStatus(.denied)
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).isEmpty)
    }

    @Test func aReminderThatIsOffSchedulesNothingAndClearsWhatWasThere() async throws {
        let rig = try await setup(enabled: false)
        let april = try CalendarDay.make(year: 2027, month: 4, day: 1)
        try await rig.client.schedule(DigestNotification(id: NotificationIDs.digest(for: april), day: april, title: "t", body: "b"))
        try await add(rig.kit, "Old", 2020, 1, 1)
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).isEmpty)
        #expect(await rig.client.scheduledHistory.count == 1, "nothing new was scheduled")
    }

    @Test func notDeterminedPermissionSchedulesNothing() async throws {
        let rig = try await setup(status: .notDetermined)
        try await add(rig.kit, "Old", 2020, 1, 1)
        await rig.scheduler.reschedule()
        #expect(await rig.client.scheduledHistory.isEmpty)
    }

    @Test func aSchedulingFailureDoesNotStopTheOtherDigestsOrTheApp() async throws {
        let rig = try await setup()
        try await add(rig.kit, "Old", 2020, 1, 1)
        await rig.client.failScheduling()
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).isEmpty)
        #expect(await rig.scheduler.rescheduleCount == 1)

        await rig.client.failScheduling(false)
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).count == 12, "the next pass writes them")
    }

    @Test func anUnreadableCabinetLeavesWhatIsPending() async throws {
        let rig = try await setup()
        try await add(rig.kit, "Old", 2020, 1, 1)
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).count == 12)

        try rig.kit.database.writer.close()
        await rig.scheduler.reschedule()
        #expect(await ids(rig.client).count == 12, "a failed read must not wipe the reminders")
    }

    // MARK: Privacy (N6, X1)

    @Test func theContentCarriesCountsOnlyNeverAName() async throws {
        let rig = try await setup()
        let box = Box(
            product: ProductRef(gtin: "03400930000014", name: "Doliprane Secret"), category: .painFever,
            locationID: rig.kit.home.id, expiry: try ExpiryDate.make(year: 2027, month: 3, day: 10), lot: "LOTSECRET",
            serial: "SERIALSECRET", createdAt: rig.kit.clock.now)
        try await rig.kit.boxes.insert(box)
        await rig.scheduler.reschedule()

        let all = await rig.client.pendingList
        #expect(!all.isEmpty)
        for digest in all {
            let text = digest.title + " " + digest.body
            for secret in ["Doliprane", "Secret", "LOTSECRET", "SERIAL", "03400930000014", "Home", "2027-03-10", "10 March"] {
                #expect(!text.contains(secret), "the digest must not contain \(secret)")
            }
            #expect(!digest.id.contains("Doliprane"))
        }
        #expect(all.first?.body == "1 box expires within 60 days")
    }
}
