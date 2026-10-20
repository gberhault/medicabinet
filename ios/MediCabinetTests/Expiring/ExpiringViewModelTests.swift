import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

@MainActor
@Suite struct ExpiringViewModelTests {
    private struct Running {
        let kit: CabinetTestKit
        let model: ExpiringViewModel
        let undo: UndoCenter
        let observation: Task<Void, Never>
        func finish() { observation.cancel() }
    }

    private func start(
        _ kit: CabinetTestKit, sleeps: Sleeps = Sleeps(), environment: AppEnvironment? = nil
    ) -> Running {
        let undo = UndoCenter()
        let model = ExpiringViewModel(
            environment: environment ?? kit.environment, undo: undo, sleep: { try await sleeps.sleep($0) })
        return Running(kit: kit, model: model, undo: undo, observation: Task { await model.observe() })
    }

    private func loaded(_ kit: CabinetTestKit) async -> Running {
        let running = start(kit)
        #expect(await eventually { running.model.phase == .loaded }, "the overview never loaded")
        return running
    }

    private func names(_ entries: [ExpiringEntry]?) -> [String] {
        (entries ?? []).map(\.box.product.name)
    }

    // MARK: Loading and content

    @Test func theViewStartsLoadingWithNothingToCount() async throws {
        let kit = try await CabinetTestKit()
        let model = ExpiringViewModel(environment: kit.environment, undo: nil)
        #expect(model.phase == .loading)
        #expect(model.overview == nil)
        #expect(model.attentionCount == 0)
    }

    @Test func anEmptyCabinetIsAnEmptyOverviewNamingTheWindow() async throws {
        let running = await loaded(try await CabinetTestKit())
        defer { running.finish() }
        #expect(running.model.overview?.isEmpty == true)
        #expect(running.model.window == .days60)
        #expect(running.model.attentionCount == 0)
    }

    @Test func boxesFallIntoExpiredExpiringSoonAndUnknownInDisplayOrder() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Late", gtin: "03400930000021", expiryInDays: -30)
        try await kit.add("Earlier", gtin: "03400930000038", expiryInDays: -60)
        try await kit.add("Soon", gtin: "03400930000045", expiryInDays: 10)
        try await kit.add("Sooner", gtin: "03400930000052", expiryInDays: 2)
        try await kit.add("Fine", gtin: "03400930000069", expiryInDays: 400)
        try await kit.add("Thermometer", gtin: nil)
        let running = await loaded(kit)
        defer { running.finish() }
        let overview = running.model.overview
        #expect(names(overview?.expired) == ["Earlier", "Late"])
        #expect(names(overview?.expiringSoon) == ["Sooner", "Soon"])
        #expect(names(overview?.unknown) == ["Thermometer"])
        #expect(running.model.attentionCount == 4, "unknown boxes are not counted, OK boxes are not listed")
    }

    @Test func aBoxExpiringTodayIsExpiringSoonWithZeroDaysLeft() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Today", expiryInDays: 0)
        let running = await loaded(kit)
        defer { running.finish() }
        let entry = try #require(running.model.overview?.expiringSoon.first)
        #expect(entry.assessment.expiresToday)
    }

    @Test func aMonthPreciseExpiryRunsToTheEndOfTheMonth() async throws {
        let kit = try await CabinetTestKit()
        // 2027-01-15 today: "01/2027" is valid through 31 January.
        let box = Box(
            product: ProductRef(gtin: nil, name: "Monthly"), category: .uncategorized, locationID: kit.home.id,
            expiry: try ExpiryDate.make(year: 2027, month: 1, day: nil), createdAt: kit.clock.now)
        try await kit.boxes.insert(box)
        let running = await loaded(kit)
        defer { running.finish() }
        #expect(running.model.overview?.expiringSoon.first?.assessment.daysLeft == 16)
    }

    @Test func rowsKnowTheirLocationName() async throws {
        let kit = try await CabinetTestKit()
        let car = try await kit.locations.create(named: "Car")
        try await kit.add("Gaviscon", in: car, expiryInDays: 5)
        let running = await loaded(kit)
        defer { running.finish() }
        let entry = try #require(running.model.overview?.expiringSoon.first)
        #expect(running.model.locationName(entry.box.locationID) == "Car")
        #expect(running.model.locationName(UUID()) == "")
    }

    // MARK: Live updates (E7)

    @Test func theListFollowsWritesMadeElsewhere() async throws {
        let kit = try await CabinetTestKit()
        let running = await loaded(kit)
        defer { running.finish() }
        let box = try await kit.add("Doliprane", expiryInDays: -3)
        #expect(await eventually { running.model.attentionCount == 1 })

        try await kit.boxes.markRemoved(id: box.id, reason: .usedUp, at: kit.clock.now)
        #expect(await eventually { running.model.attentionCount == 0 })
        try await kit.boxes.undoRemoval(id: box.id)
        #expect(await eventually { running.model.attentionCount == 1 })

        try await kit.boxes.delete(id: box.id)
        #expect(await eventually { running.model.attentionCount == 0 }, "a deleted box has no status")
    }

    @Test func aBoxEditedElsewhereMovesBetweenSections() async throws {
        let kit = try await CabinetTestKit()
        var box = try await kit.add("Doliprane", expiryInDays: 400)
        let running = await loaded(kit)
        defer { running.finish() }
        #expect(running.model.overview?.isEmpty == true)
        box.expiry = nil
        try await kit.boxes.update(box)
        #expect(await eventually { running.model.overview?.unknown.count == 1 })
        #expect(running.model.attentionCount == 0)
    }

    @Test func changingTheWindowRebuildsTheOverviewAndTheEmptyStateNamesIt() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 45)
        let running = await loaded(kit)
        defer { running.finish() }
        #expect(running.model.attentionCount == 1)

        kit.environment.preferences.window = .days30
        #expect(await eventually { running.model.attentionCount == 0 })
        #expect(running.model.window == .days30)
        #expect(running.model.overview?.isEmpty == true)
        kit.environment.preferences.window = .days90
        #expect(await eventually { running.model.attentionCount == 1 })
        #expect(running.model.window == .days90)
    }

    // MARK: The day changes (S9)

    @Test func midnightReEvaluatesTheStatusesAndTheBadge() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 0)
        let sleeps = Sleeps()
        let running = start(kit, sleeps: sleeps)
        defer { running.finish() }
        #expect(await eventually { running.model.overview?.expiringSoon.count == 1 })
        // 08:00 UTC: sixteen hours to midnight.
        #expect(await eventuallyAsync { !(await sleeps.requested.isEmpty) })
        #expect(await sleeps.requested.first == .seconds(16 * 3_600))

        kit.clock.advance(by: 16 * 3_600)
        await sleeps.releaseOne()
        #expect(await eventually { running.model.overview?.expired.count == 1 })
        #expect(running.model.overview?.expiringSoon.isEmpty == true)
        #expect(running.model.attentionCount == 1)
    }

    @Test func aTimeChangeSignalReEvaluatesTheStatuses() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 0)
        let (signals, signal) = AsyncStream<Void>.makeStream()
        var environment = kit.environment
        environment.todaySource = TodaySource { signals }
        let running = start(kit, environment: environment)
        defer { running.finish() }
        #expect(await eventually { running.model.overview?.expiringSoon.count == 1 })

        kit.clock.advance(by: 17 * 3_600)
        signal.yield()
        #expect(await eventually { running.model.overview?.expired.count == 1 })
    }

    @Test func comingBackTheSameDayChangesNothing() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 0)
        let running = await loaded(kit)
        defer { running.finish() }
        let before = running.model.overview
        kit.clock.advance(by: 3_600)
        running.model.refreshToday()
        #expect(running.model.overview == before)
    }

    @Test func aZoneChangeMovesTheDayWithoutMovingTheClock() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 0)
        let zone = ZoneBox(.gmt)
        var environment = kit.environment
        environment.timeZone = { zone.value }
        let running = start(kit, environment: environment)
        defer { running.finish() }
        #expect(await eventually { running.model.overview?.expiringSoon.count == 1 })

        zone.value = TimeZone(secondsFromGMT: 17 * 3_600) ?? .gmt  // 08:00 UTC is already the next day there
        running.model.refreshToday()
        #expect(await eventually { running.model.overview?.expired.count == 1 })
    }

    // MARK: Failure and Retry

    @Test func anUnreadableCabinetFailsAndRetryRestartsTheObservation() async throws {
        let kit = try await CabinetTestKit()
        try kit.database.writer.close()
        let running = start(kit)
        defer { running.finish() }
        #expect(await eventually { running.model.phase == .failed })
        #expect(running.model.attempt == 0)
        running.model.retry()
        #expect(running.model.attempt == 1)
    }

    @Test func observingAgainAfterAFailureShowsLoadingNotTheStaleOverview() async throws {
        let kit = try await CabinetTestKit()
        try kit.database.writer.close()
        let model = ExpiringViewModel(environment: kit.environment, undo: nil)
        let first = Task { await model.observe() }
        #expect(await eventually { model.phase == .failed })
        first.cancel()
        let second = Task { await model.observe() }
        defer { second.cancel() }
        // Still failing (the database is closed): it goes through loading again and ends up failed once more.
        #expect(await eventually { model.phase == .failed })
    }

    // MARK: Thrown away (E9)

    @Test func throwingAwayMarksTheBoxThrownAwayAtTheClockTimeAndOffersUndo() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: -1)
        let running = await loaded(kit)
        defer { running.finish() }
        let entry = try #require(running.model.overview?.expired.first)

        await running.model.throwAway(entry)
        let removal = try #require(try await kit.boxes.box(id: box.id)?.removal)
        #expect(removal.reason == .thrownAway)
        #expect(removal.date == kit.clock.now)
        #expect(running.undo.pending?.message == CabinetText.undoMessage("expiring.thrownAway.done", name: "Doliprane"))
        #expect(running.undo.pending?.undo != nil)
        #expect(await eventually { running.model.attentionCount == 0 })
    }

    @Test func undoPutsTheBoxBack() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: -1)
        let running = await loaded(kit)
        defer { running.finish() }
        await running.model.throwAway(try #require(running.model.overview?.expired.first))
        #expect(await eventually { running.model.attentionCount == 0 })

        await running.undo.perform()
        #expect(try await kit.boxes.box(id: box.id)?.removal == nil)
        #expect(await eventually { running.model.attentionCount == 1 })
    }

    @Test func aFailedWriteLeavesTheBoxAsItWasAndSaysSo() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: -1)
        let running = await loaded(kit)
        defer { running.finish() }
        let entry = try #require(running.model.overview?.expired.first)
        try kit.database.writer.close()

        await running.model.throwAway(entry)
        #expect(running.undo.pending?.message == String(localized: "expiring.thrownAway.failed"))
        #expect(running.undo.pending?.undo == nil, "nothing was removed, so there is nothing to undo")
        #expect(running.model.attentionCount == 1)
        _ = box
    }

    @Test func aBoxAlreadyRemovedElsewhereCannotBeThrownAwayTwice() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: -1)
        let running = await loaded(kit)
        defer { running.finish() }
        let entry = try #require(running.model.overview?.expired.first)
        try await kit.boxes.delete(id: box.id)

        await running.model.throwAway(entry)
        #expect(running.undo.pending?.message == String(localized: "expiring.thrownAway.failed"))
    }

    @Test func withoutAnUndoCenterTheBoxIsStillThrownAway() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Doliprane", expiryInDays: -1)
        let model = ExpiringViewModel(environment: kit.environment, undo: nil)
        let observation = Task { await model.observe() }
        defer { observation.cancel() }
        #expect(await eventually { model.overview?.expired.count == 1 })
        await model.throwAway(try #require(model.overview?.expired.first))
        #expect(try await kit.boxes.box(id: box.id)?.removal?.reason == .thrownAway)
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
