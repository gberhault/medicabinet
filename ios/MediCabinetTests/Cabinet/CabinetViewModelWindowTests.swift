import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

/// The Cabinet screen follows the "expiring soon" window and the time-change signals while it is on screen.
@MainActor
@Suite struct CabinetViewModelWindowTests {
    private struct Running {
        let model: CabinetViewModel
        let observation: Task<Void, Never>
        func finish() { observation.cancel() }
    }

    private func loaded(_ kit: CabinetTestKit) async throws -> Running {
        let model = CabinetViewModel(environment: kit.environment)
        let observation = Task { await model.observe() }
        #expect(await eventually { model.phase == .loaded }, "the cabinet never loaded")
        return Running(model: model, observation: observation)
    }

    // MARK: Window and time changes (W2, S9)

    @Test func changingTheWindowReEvaluatesTheStatusesAtOnce() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 45)
        let running = try await loaded(kit)
        defer { running.finish() }
        #expect(running.model.snapshot.sections.first?.rows.first?.status == .expiringSoon, "60 days by default")

        kit.environment.preferences.window = .days30
        #expect(await eventually { running.model.snapshot.sections.first?.rows.first?.status == .ok })
        kit.environment.preferences.window = .days90
        #expect(await eventually { running.model.snapshot.sections.first?.rows.first?.status == .expiringSoon })
    }

    @Test func aWindowChosenBeforeTheScreenOpensIsUsedFromTheFirstSnapshot() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 45)
        kit.environment.preferences.window = .days30
        let running = try await loaded(kit)
        defer { running.finish() }
        #expect(running.model.snapshot.sections.first?.rows.first?.status == .ok)
    }

    @Test func aTimeChangeSignalReEvaluatesTheStatuses() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Doliprane", expiryInDays: 61)
        let (signals, signal) = AsyncStream<Void>.makeStream()
        var environment = kit.environment
        environment.todaySource = TodaySource { signals }
        let model = CabinetViewModel(environment: environment)
        let observation = Task { await model.observe() }
        defer { observation.cancel() }
        #expect(await eventually { model.phase == .loaded })
        #expect(model.snapshot.sections.first?.rows.first?.status == .ok)

        kit.clock.advance(by: 3_600 * 17)  // past midnight: 60 days left
        signal.yield()
        #expect(await eventually { model.snapshot.sections.first?.rows.first?.status == .expiringSoon })
    }
}
