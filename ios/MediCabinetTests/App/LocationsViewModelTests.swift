import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport
import Testing
@testable import MediCabinet

@MainActor
@Suite struct LocationsViewModelTests {
    /// The real GRDB repositories on an in-memory database: the model is tested against what ships.
    private struct Setup {
        let database: AppDatabase
        let locations: GRDBLocationRepository
        let boxes: GRDBBoxRepository
        let clock: MutableClock
        let undo: UndoCenter
        let model: LocationsViewModel
        let home: Location
        let observation: Task<Void, Never>

        func finish() { observation.cancel() }
    }

    private func setup() async throws -> Setup {
        let database = try AppDatabase.inMemory()
        let clock = MutableClock(Date(timeIntervalSince1970: 1_800_000_000))
        let locations = GRDBLocationRepository(database: database, clock: clock)
        let boxes = GRDBBoxRepository(database: database, clock: clock)
        let home = try await locations.ensureDefault(named: "Home")
        let undo = UndoCenter()
        let model = LocationsViewModel(repository: locations, undo: undo)
        let observation = Task { await model.observe() }
        let ready = await eventually { model.phase == .loaded }
        try #require(ready, "the list never loaded")
        return Setup(
            database: database, locations: locations, boxes: boxes, clock: clock, undo: undo, model: model,
            home: home, observation: observation)
    }

    private func eventually(_ condition: () -> Bool) async -> Bool {
        for _ in 0..<500 {
            if condition() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return false
    }

    private func names(_ model: LocationsViewModel) -> [String] { model.locations.map(\.name) }

    private func box(in location: Location) -> Box {
        Box(product: ProductRef(gtin: "03400930000014", name: "Doliprane"), locationID: location.id,
            serial: UUID().uuidString.prefix(12).description, createdAt: Date(timeIntervalSince1970: 1_800_000_000))
    }

    @Test func theListLoadsAndFollowsChangesMadeElsewhere() async throws {
        let env = try await setup()
        defer { env.finish() }
        #expect(names(env.model) == ["Home"])
        _ = try await env.locations.create(named: "Garage")
        #expect(await eventually { names(env.model) == ["Home", "Garage"] })
    }

    @Test func addingALocationPutsItLast() async throws {
        let env = try await setup()
        defer { env.finish() }
        #expect(await env.model.add("  Kitchen ") == nil)
        #expect(await eventually { names(env.model) == ["Home", "Kitchen"] })
    }

    @Test func aRefusedNameSaysWhyAndChangesNothing() async throws {
        let env = try await setup()
        defer { env.finish() }
        #expect(await env.model.add("   ") == .empty)
        #expect(await env.model.add(String(repeating: "a", count: 51)) == .tooLong)
        #expect(await env.model.add(" hôme ") == .duplicate)
        #expect(names(env.model) == ["Home"])
        #expect(!env.model.changeFailed)
    }

    @Test func renamingKeepsThePlaceAndAllowsACaseChangeOfItsOwnName() async throws {
        let env = try await setup()
        defer { env.finish() }
        _ = await env.model.add("Garage")
        #expect(await eventually { env.model.locations.count == 2 })
        let garage = try #require(env.model.locations.last)
        #expect(await env.model.rename(garage, to: "GARAGE") == nil)
        #expect(await eventually { names(env.model) == ["Home", "GARAGE"] })
        #expect(await env.model.rename(garage, to: "home") == .duplicate)
    }

    @Test func movingALocationUpOrDownReordersTheList() async throws {
        let env = try await setup()
        defer { env.finish() }
        _ = await env.model.add("Garage")
        _ = await env.model.add("Car")
        #expect(await eventually { names(env.model) == ["Home", "Garage", "Car"] })
        await env.model.moveUp(try #require(env.model.locations.last))
        #expect(await eventually { names(env.model) == ["Home", "Car", "Garage"] })
        await env.model.moveDown(try #require(env.model.locations.first))
        #expect(await eventually { names(env.model) == ["Car", "Home", "Garage"] })
        // The ends stay where they are.
        await env.model.moveUp(try #require(env.model.locations.first))
        await env.model.moveDown(try #require(env.model.locations.last))
        #expect(names(env.model) == ["Car", "Home", "Garage"])
    }

    @Test func makingALocationTheDefaultMovesTheMarker() async throws {
        let env = try await setup()
        defer { env.finish() }
        _ = await env.model.add("Garage")
        #expect(await eventually { env.model.locations.count == 2 })
        await env.model.makeDefault(try #require(env.model.locations.last))
        #expect(await eventually { env.model.locations.filter(\.isDefault).map(\.name) == ["Garage"] })
    }

    @Test func deletingAnEmptyLocationGoesAtOnceAndUndoBringsItBack() async throws {
        let env = try await setup()
        defer { env.finish() }
        _ = await env.model.add("Garage")
        #expect(await eventually { env.model.locations.count == 2 })
        let garage = try #require(env.model.locations.last)
        await env.model.requestDeletion(of: garage)
        #expect(await eventually { names(env.model) == ["Home"] })
        #expect(env.model.deletion == nil)
        #expect(env.undo.pending?.message.contains("Garage") == true)
        await env.undo.perform()
        #expect(await eventually { names(env.model) == ["Home", "Garage"] })
    }

    @Test func deletingALocationWithBoxesAsksWhereTheyGoThenMovesThem() async throws {
        let env = try await setup()
        defer { env.finish() }
        _ = await env.model.add("Garage")
        #expect(await eventually { env.model.locations.count == 2 })
        let garage = try #require(env.model.locations.last)
        let first = box(in: garage), second = box(in: garage)
        try await env.boxes.insert(first)
        try await env.boxes.insert(second)

        await env.model.requestDeletion(of: garage)
        let request = try #require(env.model.deletion)
        #expect(request.boxCount == 2)
        #expect(request.destinations.map(\.name) == ["Home"])
        #expect(env.model.suggestedDestination(for: request) == env.home.id)
        #expect(names(env.model) == ["Home", "Garage"], "nothing changes until the user confirms")

        await env.model.confirm(request, movingBoxesTo: env.home.id)
        #expect(await eventually { names(env.model) == ["Home"] })
        #expect(env.model.deletion == nil)
        #expect(env.undo.pending == nil, "a deletion that moved boxes is confirmed, not undone")
        let moved = try await env.boxes.activeBoxes()
        #expect(Set(moved.map(\.locationID)) == [env.home.id])
        #expect(moved.count == 2)
    }

    @Test func theDefaultLocationIsNeverDeleted() async throws {
        let env = try await setup()
        defer { env.finish() }
        await env.model.requestDeletion(of: env.home)
        #expect(env.model.deletion == nil)
        #expect(env.undo.pending == nil)
        #expect(names(env.model) == ["Home"])
    }

    @Test func cancellingTheDeletionChangesNothing() async throws {
        let env = try await setup()
        defer { env.finish() }
        _ = await env.model.add("Garage")
        #expect(await eventually { env.model.locations.count == 2 })
        let garage = try #require(env.model.locations.last)
        try await env.boxes.insert(box(in: garage))
        await env.model.requestDeletion(of: garage)
        #expect(env.model.deletion != nil)
        env.model.deletion = nil
        #expect(names(env.model) == ["Home", "Garage"])
        #expect(try await env.locations.cabinetBoxCount(in: garage.id) == 1)
    }

    @Test func aFailedWriteRaisesTheAlertAndKeepsTheList() async throws {
        let env = try await setup()
        defer { env.finish() }
        try env.database.writer.close()
        #expect(await env.model.add("Garage") == nil)
        #expect(env.model.changeFailed)
        #expect(names(env.model) == ["Home"])
    }

    @Test func aListThatCannotBeReadShowsTheFailedState() async throws {
        let database = try AppDatabase.inMemory()
        let clock = MutableClock(Date(timeIntervalSince1970: 1_800_000_000))
        let repository = GRDBLocationRepository(database: database, clock: clock)
        try database.writer.close()
        let model = LocationsViewModel(repository: repository, undo: nil)
        await model.observe()
        #expect(model.phase == .failed)
    }

    @Test(arguments: [("fr", 1, "1 boîte"), ("fr", 3, "3 boîtes"), ("en", 1, "1 box"), ("en", 3, "3 boxes")])
    func theDeletionMessageIsPluralAwareInEachLanguage(code: String, count: Int, expected: String) async throws {
        let env = try await setup()
        defer { env.finish() }
        let path = try #require(Bundle.main.path(forResource: code, ofType: "lproj"))
        let bundle = try #require(Bundle(path: path))
        let request = LocationsViewModel.Deletion(location: env.home, boxCount: count, destinations: [])
        let message = env.model.deletionMessage(for: request, bundle: bundle, locale: Locale(identifier: code))
        #expect(message.contains(expected), "message was \(message)")
    }
}
