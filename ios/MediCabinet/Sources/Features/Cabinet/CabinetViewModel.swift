import MediCabinetCore
import Observation
import SwiftUI

/// Presentation state of the Cabinet tab. The boxes and locations follow the repositories (only while the screen is
/// visible); rows are built by the core's `CabinetRows.build`, off the main thread; the latest result wins.
@MainActor @Observable
final class CabinetViewModel {
    enum Phase: Equatable {
        case loading, loaded, failed
    }

    private(set) var phase: Phase = .loading
    private(set) var snapshot = CabinetSnapshot.empty
    private(set) var locations: [Location] = []
    /// The filters in force: search (debounced), location and expiry status. Never stored.
    private(set) var filter = CabinetFilter()
    /// What the search field shows; `filter.query` follows it after the debounce.
    var searchText = "" {
        didSet { if searchText != oldValue { scheduleQuery() } }
    }
    /// Bumped by Retry; the view restarts `observe()` when it changes.
    private(set) var attempt = 0
    /// How many boxes are in the cabinet before any filter (0 = the empty-cabinet state).
    private(set) var cabinetBoxCount = 0

    static let searchDebounce: Duration = .milliseconds(150)

    private let boxes: any BoxRepository
    private let locationRepository: any LocationRepository
    private let environment: AppEnvironment
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private var cabinetBoxes: [Box]?
    @ObservationIgnored private var hasLocations = false
    @ObservationIgnored private var today: CalendarDay
    @ObservationIgnored private var debounce: Task<Void, Never>?
    @ObservationIgnored private var build: Task<Void, Never>?

    /// `sleep` is injected so tests neither wait for the debounce nor for midnight.
    init(
        environment: AppEnvironment,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.environment = environment
        boxes = environment.boxes
        locationRepository = environment.locations
        today = environment.today()
        self.sleep = sleep
    }

    // MARK: Observation

    /// Follows the boxes and the locations, and re-evaluates the statuses at midnight, until the task is cancelled
    /// (the screen went away).
    func observe() async {
        if phase == .failed { phase = .loading }
        refreshToday()
        async let boxes: Void = followBoxes()
        async let locations: Void = followLocations()
        async let midnight: Void = followMidnight()
        _ = await (boxes, locations, midnight)
    }

    func retry() {
        attempt += 1
    }

    private func followBoxes() async {
        do {
            for try await current in boxes.observeCabinet() {
                cabinetBoxes = current
                cabinetBoxCount = current.count
                rebuild()
            }
        } catch {
            fail(error)
        }
    }

    private func followLocations() async {
        do {
            for try await current in locationRepository.observeAll() {
                locations = current
                hasLocations = true
                // A location that no longer exists cannot stay selected: back to "All locations" (L10).
                if let selected = filter.locationID, !current.contains(where: { $0.id == selected }) {
                    filter.locationID = nil
                }
                rebuild()
            }
        } catch {
            fail(error)
        }
    }

    private func followMidnight() async {
        while !Task.isCancelled {
            do { try await sleep(.seconds(environment.secondsUntilMidnight())) } catch { return }
            refreshToday()
        }
    }

    private func fail(_ error: any Error) {
        guard !(error is CancellationError) else { return }
        // The failure kind only: never a name, a code or a date of the cabinet.
        Log.persistence.error("Observing the cabinet failed: \(String(describing: type(of: error)))")
        phase = .failed
    }

    /// The day changed (midnight, or the app came back to the foreground): statuses are computed again.
    func refreshToday() {
        let day = environment.today()
        guard day != today else { return }
        today = day
        rebuild()
    }

    // MARK: Filters

    func selectLocation(_ id: Location.ID?) {
        guard filter.locationID != id else { return }
        filter.locationID = id
        rebuild()
    }

    func selectStatus(_ status: ExpiryStatus?) {
        guard filter.status != status else { return }
        filter.status = status
        rebuild()
    }

    func clearFilters() {
        searchText = ""
        debounce?.cancel()
        filter = CabinetFilter()
        rebuild()
    }

    private func scheduleQuery() {
        debounce?.cancel()
        let text = searchText
        debounce = Task { [sleep] in
            do { try await sleep(Self.searchDebounce) } catch { return }
            guard !Task.isCancelled, filter.query != text else { return }
            filter.query = text
            rebuild()
        }
    }

    // MARK: Derived state

    var isEmptyCabinet: Bool { phase == .loaded && cabinetBoxCount == 0 }
    var hasNoResults: Bool { phase == .loaded && cabinetBoxCount > 0 && snapshot.boxCount == 0 }

    /// The active filters as short texts (the search text, the location name, the status).
    var activeFilterParts: [String] {
        var parts: [String] = []
        let query = TextNormalizer.trimmed(filter.query)
        if !query.isEmpty { parts.append("\u{201C}\(query)\u{201D}") }
        if let id = filter.locationID, let location = locations.first(where: { $0.id == id }) {
            parts.append(location.name)
        }
        if let status = filter.status {
            parts.append(String(localized: String.LocalizationValue(ExpiryStatusStyle.key(status))))
        }
        return parts
    }

    func locationName(_ id: Location.ID) -> String {
        locations.first { $0.id == id }?.name ?? ""
    }

    /// The status of a box today (the core rule, the "expiring soon" window).
    func status(of box: Box) -> ExpiryStatus {
        environment.expiryStatus(of: box.expiry, on: today)
    }

    /// The row as it is now, so the product's boxes screen follows the list; `nil` once its last box left.
    func row(_ id: ProductRowID) -> ProductRow? {
        for section in snapshot.sections {
            if let row = section.rows.first(where: { $0.id == id }) { return row }
        }
        return nil
    }

    // MARK: Building

    private func rebuild() {
        guard let source = cabinetBoxes, hasLocations else { return }
        build?.cancel()
        let filter = filter
        let order = environment.categoryOrder
        let day = today
        let environment = environment
        build = Task {
            // The core is pure and synchronous; the work stays off the main thread.
            let result = await Task.detached(priority: .userInitiated) {
                CabinetRows.build(boxes: source, filter: filter, categoryOrder: order) {
                    environment.expiryStatus(of: $0.expiry, on: day)
                }
            }.value
            guard !Task.isCancelled else { return }
            snapshot = result
            phase = .loaded
        }
    }
}
