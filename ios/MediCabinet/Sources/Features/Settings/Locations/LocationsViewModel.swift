import MediCabinetCore
import Observation
import SwiftUI

/// Presentation state of the Locations screen. Every change goes through the repository; the list follows the
/// repository's observation, so nothing here caches a stale copy.
@MainActor @Observable
final class LocationsViewModel {
    enum Phase: Equatable {
        case loading, loaded, failed
    }

    /// Why a typed name was refused; the sheet shows the matching message.
    enum NameProblem: Equatable {
        case empty, tooLong, duplicate
    }

    /// How a repository write ended.
    private enum Outcome {
        case done
        /// The name is already taken (decided by the repository, e.g. after a race).
        case refused(NameProblem)
        /// Nothing was changed; the alert is raised.
        case failed

        var problem: NameProblem? {
            if case .refused(let problem) = self { return problem }
            return nil
        }
    }

    /// A deletion that needs the user to say where the boxes go.
    struct Deletion: Identifiable {
        let location: Location
        let boxCount: Int
        let destinations: [Location]
        var id: Location.ID { location.id }
    }

    private(set) var locations: [Location] = []
    private(set) var phase: Phase = .loading
    var deletion: Deletion?
    /// A write failed and nothing was changed: the screen shows an alert.
    var changeFailed = false

    private let repository: any LocationRepository
    private let undo: UndoCenter?

    init(repository: any LocationRepository, undo: UndoCenter?) {
        self.repository = repository
        self.undo = undo
    }

    /// Follows the repository until the screen goes away.
    func observe() async {
        phase = .loading
        do {
            for try await current in repository.observeAll() {
                locations = current
                phase = .loaded
            }
        } catch {
            guard !(error is CancellationError) else { return }
            Log.persistence.error("Observing the locations failed: \(String(describing: type(of: error)))")
            phase = .failed
        }
    }

    // MARK: Names

    /// `nil` when `raw` is acceptable for a new location, or for renaming `location`.
    func problem(with raw: String, renaming location: Location? = nil) -> NameProblem? {
        let others = locations.filter { $0.id != location?.id }.map(\.name)
        do {
            _ = try LocationName.validate(raw, existing: others)
            return nil
        } catch {
            switch error {
            case .empty: return .empty
            case .tooLong: return .tooLong
            case .duplicateName: return .duplicate
            }
        }
    }

    /// Returns the problem when the name is refused, `nil` when the location was added.
    func add(_ raw: String) async -> NameProblem? {
        if let problem = problem(with: raw) { return problem }
        return await write { _ = try await self.repository.create(named: raw) }.problem
    }

    func rename(_ location: Location, to raw: String) async -> NameProblem? {
        if let problem = problem(with: raw, renaming: location) { return problem }
        return await write { try await self.repository.rename(id: location.id, to: raw) }.problem
    }

    // MARK: Order and default

    func move(fromOffsets source: IndexSet, toOffset destination: Int) async {
        var ids = locations.map(\.id)
        ids.move(fromOffsets: source, toOffset: destination)
        await reorder(ids)
    }

    func moveUp(_ location: Location) async {
        guard let index = locations.firstIndex(of: location), index > 0 else { return }
        await move(fromOffsets: IndexSet(integer: index), toOffset: index - 1)
    }

    func moveDown(_ location: Location) async {
        guard let index = locations.firstIndex(of: location), index < locations.count - 1 else { return }
        await move(fromOffsets: IndexSet(integer: index), toOffset: index + 2)
    }

    func makeDefault(_ location: Location) async {
        await write { try await self.repository.setDefault(id: location.id) }
    }

    private func reorder(_ ids: [Location.ID]) async {
        await write { try await self.repository.reorder(ids) }
    }

    // MARK: Deletion

    /// An empty location goes at once (with Undo); one that holds boxes asks where they go. The default location
    /// is never deleted.
    func requestDeletion(of location: Location) async {
        guard !location.isDefault else { return }
        do {
            let count = try await repository.cabinetBoxCount(in: location.id)
            if count == 0 {
                await deleteEmpty(location)
            } else {
                deletion = Deletion(
                    location: location, boxCount: count, destinations: locations.filter { $0.id != location.id })
            }
        } catch {
            fail(error)
        }
    }

    /// The pre-selected destination of a deletion: the default location when it is not the one deleted.
    func suggestedDestination(for request: Deletion) -> Location.ID? {
        (request.destinations.first(where: \.isDefault) ?? request.destinations.first)?.id
    }

    /// "This location holds 3 boxes. Where should they go?" - plural-aware; `bundle` and `locale` let tests check each language.
    func deletionMessage(for request: Deletion, bundle: Bundle = .main, locale: Locale = .current) -> String {
        String(
            format: bundle.localizedString(forKey: "locations.delete.message", value: nil, table: nil),
            locale: locale, request.boxCount)
    }

    func confirm(_ request: Deletion, movingBoxesTo destination: Location.ID) async {
        deletion = nil
        await write { try await self.repository.deleteLocation(id: request.location.id, movingBoxesTo: destination) }
    }

    private func deleteEmpty(_ location: Location) async {
        let repository = repository
        let outcome = await write { try await repository.deleteLocation(id: location.id, movingBoxesTo: nil) }
        guard case .done = outcome else { return }
        undo?.show(String(format: String(localized: "locations.deleted"), location.name)) {
            try? await repository.undoDeleteLocation(id: location.id)
        }
    }

    // MARK: Writing

    /// Runs a repository write. A refused duplicate name is reported to the caller; any other failure leaves the data
    /// as it was and raises the alert (the failure kind is logged, never a name).
    @discardableResult
    private func write(_ work: () async throws -> Void) async -> Outcome {
        do {
            try await work()
            return .done
        } catch RepositoryError.duplicateName {
            return .refused(.duplicate)
        } catch {
            fail(error)
            return .failed
        }
    }

    private func fail(_ error: any Error) {
        Log.persistence.error("A location change failed: \(String(describing: type(of: error)))")
        changeFailed = true
    }
}
