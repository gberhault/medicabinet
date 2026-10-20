import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

/// Records the sleeps the model asks for. Short ones (the search debounce) return at once; long ones (until midnight)
/// wait until the test opens them, so the day change is deterministic.
private actor Sleeps {
    private(set) var requested: [Duration] = []
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var released = 0

    func sleep(_ duration: Duration) async throws {
        requested.append(duration)
        if duration < .seconds(1) { return }
        if released > 0 {
            released -= 1
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func releaseOne() {
        if waiters.isEmpty {
            released += 1
        } else {
            waiters.removeFirst().resume()
        }
    }
}

@MainActor
@Suite struct CabinetViewModelTests {
    private struct Running {
        let kit: CabinetTestKit
        let model: CabinetViewModel
        let observation: Task<Void, Never>
        func finish() { observation.cancel() }
    }

    private func start(_ kit: CabinetTestKit, sleeps: Sleeps = Sleeps()) async -> (Running, Sleeps) {
        let model = CabinetViewModel(environment: kit.environment, sleep: { try await sleeps.sleep($0) })
        let observation = Task { await model.observe() }
        return (Running(kit: kit, model: model, observation: observation), sleeps)
    }

    private func loaded(_ kit: CabinetTestKit) async throws -> Running {
        let (running, _) = await start(kit)
        #expect(await eventually { running.model.phase == .loaded }, "the cabinet never loaded")
        return running
    }

    private func rowNames(_ model: CabinetViewModel) -> [String] {
        model.snapshot.sections.flatMap { $0.rows.map(\.name) }
    }

    // MARK: Loading, content

    @Test func theCabinetIsLoadingUntilTheFirstSnapshotIsBuilt() async throws {
        let kit = try await CabinetTestKit()
        let model = CabinetViewModel(environment: kit.environment)
        #expect(model.phase == .loading)
        #expect(!model.isEmptyCabinet)
        #expect(!model.hasNoResults)
    }

    @Test func anEmptyCabinetShowsTheEmptyStateNotNoResults() async throws {
        let running = try await loaded(try await CabinetTestKit())
        defer { running.finish() }
        #expect(running.model.isEmptyCabinet)
        #expect(!running.model.hasNoResults)
        #expect(running.model.snapshot.boxCount == 0)
    }

    @Test func rowsAreGroupedByCategoryInTaxonomyOrderWithUncategorizedLast() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Gaviscon", gtin: "03400930000021", category: .digestion)
        try await kit.add("Mystery", gtin: nil)
        try await kit.add("Doliprane", category: .painFever, expiryInDays: 400)
        let running = try await loaded(kit)
        defer { running.finish() }
        let categories = running.model.snapshot.sections.map(\.category)
        #expect(categories == [.painFever, .digestion, .uncategorized])
        #expect(running.model.snapshot.boxCount == 3)
        #expect(running.model.cabinetBoxCount == 3)
        #expect(!running.model.isEmptyCabinet)
    }

    @Test func boxesOfTheSameProductFormOneRowWithTheirCount() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", category: .painFever, expiryInDays: 400)
        try await kit.add("Doliprane", category: .painFever, expiryInDays: 40)
        let running = try await loaded(kit)
        defer { running.finish() }
        let row = try #require(running.model.snapshot.sections.first?.rows.first)
        #expect(running.model.snapshot.sections.count == 1)
        #expect(row.boxes.count == 2)
        #expect(row.status == .expiringSoon)
        #expect(running.model.row(row.id) == row)
    }

    @Test func aMissingExpiryIsNeverPresentedAsOK() async throws {
        let kit = try await CabinetTestKit()
        let box = try await kit.add("Thermometer", gtin: nil)
        let running = try await loaded(kit)
        defer { running.finish() }
        #expect(running.model.status(of: box) == .unknown)
        #expect(running.model.snapshot.sections.first?.rows.first?.status == .unknown)
    }

    @Test func theListFollowsWritesMadeElsewhere() async throws {
        let kit = try await CabinetTestKit()
        let running = try await loaded(kit)
        defer { running.finish() }
        let box = try await kit.add("Doliprane", expiryInDays: 400)
        #expect(await eventually { rowNames(running.model) == ["Doliprane"] })
        try await kit.boxes.markRemoved(id: box.id, reason: .usedUp, at: kit.clock.now)
        #expect(await eventually { running.model.isEmptyCabinet })
    }

    // MARK: Failure and Retry

    @Test func aCabinetThatCannotBeReadShowsTheFailedStateAndRetryRestartsIt() async throws {
        let kit = try await CabinetTestKit()
        try kit.database.writer.close()
        let model = CabinetViewModel(environment: kit.environment)
        let observation = Task { await model.observe() }
        defer { observation.cancel() }
        #expect(await eventually { model.phase == .failed })
        #expect(model.attempt == 0)
        model.retry()
        #expect(model.attempt == 1)
    }

    // MARK: Search

    @Test func searchWaitsForTheDebounceAndMatchesEveryWordWithoutAccents() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("DOLIPRANE 1000 mg", category: .painFever, expiryInDays: 400)
        try await kit.add("EFFÉRALGAN", gtin: "03400930000021", category: .painFever, expiryInDays: 400)
        let running = try await loaded(kit)
        defer { running.finish() }
        running.model.searchText = "doli 1000"
        #expect(running.model.filter.query.isEmpty, "the filter changes only after the debounce")
        #expect(await eventually { rowNames(running.model) == ["DOLIPRANE 1000 mg"] })
        #expect(running.model.snapshot.boxCount == 1)
        running.model.searchText = "efferalgan"
        #expect(await eventually { rowNames(running.model) == ["EFFÉRALGAN"] })
    }

    @Test func theSearchDebounceIs150Milliseconds() async throws {
        let kit = try await CabinetTestKit()
        let (running, sleeps) = await start(kit)
        defer { running.finish() }
        #expect(await eventually { running.model.phase == .loaded })
        running.model.searchText = "a"
        #expect(await eventually { running.model.filter.query == "a" })
        #expect(await sleeps.requested.contains(.milliseconds(150)))
    }

    @Test func noMatchShowsNoResultsAndClearBringsEverythingBack() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 400)
        let running = try await loaded(kit)
        defer { running.finish() }
        running.model.searchText = "zzz"
        #expect(await eventually { running.model.hasNoResults })
        #expect(!running.model.isEmptyCabinet, "a cabinet with boxes is not the empty cabinet")
        #expect(running.model.activeFilterParts == ["\u{201C}zzz\u{201D}"])

        running.model.clearFilters()
        #expect(running.model.searchText.isEmpty)
        #expect(!running.model.filter.isActive)
        #expect(await eventually { rowNames(running.model) == ["Doliprane"] })
        #expect(!running.model.hasNoResults)
    }

    @Test func aBlankSearchFiltersNothing() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 400)
        let running = try await loaded(kit)
        defer { running.finish() }
        running.model.searchText = "   "
        #expect(await eventually { running.model.filter.query == "   " })
        #expect(await eventually { rowNames(running.model) == ["Doliprane"] })
        #expect(running.model.activeFilterParts.isEmpty)
    }

    // MARK: Location and status filters

    @Test func theLocationFilterKeepsOnlyThatLocationsBoxes() async throws {
        let kit = try await CabinetTestKit()
        let car = try await kit.locations.create(named: "Car")
        try await kit.add("Doliprane", in: kit.home, expiryInDays: 400, serial: "S1")
        try await kit.add("Doliprane", in: car, expiryInDays: 400, serial: "S2")
        let running = try await loaded(kit)
        defer { running.finish() }
        #expect(await eventually { running.model.locations.count == 2 })
        running.model.selectLocation(car.id)
        #expect(await eventually { running.model.snapshot.boxCount == 1 })
        #expect(running.model.activeFilterParts == ["Car"])
        #expect(running.model.locationName(car.id) == "Car")
    }

    @Test func theStatusFilterKeepsRowsWithAMatchingBox() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Old", gtin: "03400930000021", expiryInDays: -3)
        try await kit.add("Fine", expiryInDays: 400)
        let running = try await loaded(kit)
        defer { running.finish() }
        running.model.selectStatus(.expired)
        #expect(await eventually { rowNames(running.model) == ["Old"] })
        running.model.selectStatus(.ok)
        #expect(await eventually { rowNames(running.model) == ["Fine"] })
        running.model.selectStatus(.unknown)
        #expect(await eventually { running.model.hasNoResults })
    }

    @Test func aDeletedLocationFallsBackToAllLocations() async throws {
        let kit = try await CabinetTestKit()
        let car = try await kit.locations.create(named: "Car")
        try await kit.add("Doliprane", in: car, expiryInDays: 400)
        let running = try await loaded(kit)
        defer { running.finish() }
        #expect(await eventually { running.model.locations.count == 2 })
        running.model.selectLocation(car.id)
        #expect(await eventually { running.model.filter.locationID == car.id })

        try await kit.locations.deleteLocation(id: car.id, movingBoxesTo: kit.home.id)
        #expect(await eventually { running.model.filter.locationID == nil })
        #expect(await eventually { running.model.snapshot.boxCount == 1 })
    }

    @Test func filtersCombineAndTheSummaryListsThem() async throws {
        let kit = try await CabinetTestKit()
        let car = try await kit.locations.create(named: "Car")
        try await kit.add("Doliprane", in: car, expiryInDays: -1, serial: "S1")
        try await kit.add("Doliprane", in: kit.home, expiryInDays: -1, serial: "S2")
        let running = try await loaded(kit)
        defer { running.finish() }
        #expect(await eventually { running.model.locations.count == 2 })
        running.model.selectLocation(car.id)
        running.model.selectStatus(.expired)
        #expect(await eventually { running.model.snapshot.boxCount == 1 })
        #expect(running.model.activeFilterParts.count == 2)
        #expect(running.model.activeFilterParts.first == "Car")
    }

    // MARK: Day change

    @Test func theStatusIsComputedAgainWhenTheDayChanges() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 61)
        let running = try await loaded(kit)
        defer { running.finish() }
        #expect(running.model.snapshot.sections.first?.rows.first?.status == .ok)

        kit.clock.advance(by: 2 * 86_400)
        running.model.refreshToday()
        #expect(await eventually { running.model.snapshot.sections.first?.rows.first?.status == .expiringSoon })
    }

    @Test func theSameDayDoesNotRebuildAnything() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 61)
        let running = try await loaded(kit)
        defer { running.finish() }
        kit.clock.advance(by: 3_600)
        running.model.refreshToday()
        #expect(running.model.snapshot.sections.first?.rows.first?.status == .ok)
    }

    @Test func midnightWhileVisibleReEvaluatesTheStatuses() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 60)
        let sleeps = Sleeps()
        let (running, _) = await start(kit, sleeps: sleeps)
        defer { running.finish() }
        #expect(await eventually { running.model.phase == .loaded })
        #expect(running.model.snapshot.sections.first?.rows.first?.status == .expiringSoon)

        // 08:00 UTC: sixteen hours to midnight.
        #expect(await eventuallyAsync { !(await sleeps.requested.isEmpty) })
        #expect(await sleeps.requested.first == .seconds(16 * 3_600))
        // Expiring in 60 days is "expiring soon"; one day later it still is, so move to a day where it expires.
        kit.clock.advance(by: 16 * 3_600 + 62 * 86_400)
        await sleeps.releaseOne()
        #expect(await eventually { running.model.snapshot.sections.first?.rows.first?.status == .expired })
    }

    @Test func secondsUntilMidnightFollowsTheInjectedClockAndZone() async throws {
        let kit = try await CabinetTestKit()
        #expect(kit.environment.secondsUntilMidnight() == 16 * 3_600)
        var paris = kit.environment
        paris.timeZone = { TimeZone(secondsFromGMT: 3_600) ?? .gmt }
        #expect(paris.secondsUntilMidnight() == 15 * 3_600)
        kit.clock.advance(by: 16 * 3_600 - 30)
        #expect(kit.environment.secondsUntilMidnight() == 60, "never less than the next whole minute, never zero")
    }
}
