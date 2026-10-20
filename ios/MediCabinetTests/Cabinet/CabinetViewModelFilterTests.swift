import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

/// The category order and the filter chips of the Cabinet header.
@MainActor
@Suite struct CabinetViewModelFilterTests {
    private func loaded(_ environment: AppEnvironment) async -> (model: CabinetViewModel, stop: () -> Void) {
        let model = CabinetViewModel(environment: environment)
        let observation = Task { await model.observe() }
        #expect(await eventually { model.phase == .loaded }, "the cabinet never loaded")
        return (model, { observation.cancel() })
    }

    @Test func theCategoryOrderFollowsTheInjectedLabels() async throws {
        let kit = try await CabinetTestKit()
        try await kit.add("Gaviscon", gtin: "03400930000021", category: .digestion)
        try await kit.add("Doliprane", category: .painFever)
        try await kit.add("Aspegic", gtin: "03400930000038", category: .other)
        var environment = kit.environment
        // "other" would sort first by its label, and must still come last.
        environment.categoryLabel = { id in
            switch id {
            case .painFever: "Aaa"
            case .digestion: "Bbb"
            default: "A"
            }
        }
        let running = await loaded(environment)
        defer { running.stop() }
        #expect(running.model.snapshot.sections.map(\.category) == [.painFever, .digestion, .other])
    }

    @Test func activeFilterCountCountsLocationAndStatusButNotTheSearch() async throws {
        let kit = try await CabinetTestKit()
        let running = await loaded(kit.environment)
        defer { running.stop() }
        let model = running.model
        #expect(model.activeFilterCount == 0)
        model.searchText = "doli"
        #expect(model.activeFilterCount == 0)
        model.selectLocation(kit.home.id)
        #expect(model.activeFilterCount == 1)
        model.selectStatus(.expired)
        #expect(model.activeFilterCount == 2)
    }

    @Test func clearingTheLocationOrTheStatusLeavesTheOtherFilterAndTheSearch() async throws {
        let kit = try await CabinetTestKit()
        let running = await loaded(kit.environment)
        defer { running.stop() }
        let model = running.model
        model.searchText = "doli"
        model.selectLocation(kit.home.id)
        model.selectStatus(.expired)

        model.clearLocation()
        #expect(model.filter.locationID == nil)
        #expect(model.filter.status == .expired)
        #expect(model.searchText == "doli")

        model.selectLocation(kit.home.id)
        model.clearStatus()
        #expect(model.filter.status == nil)
        #expect(model.filter.locationID == kit.home.id)
        #expect(model.searchText == "doli")
    }
}
