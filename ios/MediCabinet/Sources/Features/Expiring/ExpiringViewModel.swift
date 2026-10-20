import MediCabinetCore
import Observation
import SwiftUI

/// Presentation state of the Expiring tab and the source of its badge. It follows the cabinet, the locations, the
/// "expiring soon" window and the day (midnight, time zone, clock) for as long as its `observe()` task runs; the
/// overview is built by the core's `ExpiringOverview.make` off the main thread and the latest result wins.
///
/// The badge reads the same overview as the list, so the tab and the list never disagree. The model is owned by the
/// main tab view, so the badge stays live while another tab is selected.
@MainActor @Observable
final class ExpiringViewModel {
    enum Phase: Equatable {
        case loading, loaded, failed
    }

    private(set) var phase: Phase = .loading
    /// `nil` until the first result.
    private(set) var overview: ExpiringOverview?
    /// The window the overview was built with; the empty state names it.
    private(set) var window: ExpiryWindow
    private(set) var locations: [Location] = []
    /// Bumped by Retry; the owner restarts `observe()` when it changes.
    private(set) var attempt = 0

    private let environment: AppEnvironment
    private let undo: UndoCenter?
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private var cabinetBoxes: [Box]?
    @ObservationIgnored private var hasLocations = false
    @ObservationIgnored private var today: CalendarDay
    @ObservationIgnored private var build: Task<Void, Never>?

    /// `sleep` is injected so tests do not wait for midnight.
    init(
        environment: AppEnvironment, undo: UndoCenter?,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.environment = environment
        self.undo = undo
        self.sleep = sleep
        today = environment.today()
        window = environment.expiryWindow
    }

    // MARK: Observation

    /// Follows everything the lists depend on until the task is cancelled.
    func observe() async {
        if phase == .failed { phase = .loading }
        cabinetBoxes = nil
        hasLocations = false
        refreshToday()
        async let boxes: Void = followBoxes()
        async let locations: Void = followLocations()
        async let midnight: Void = followMidnight()
        async let timeChanges: Void = followTimeChanges()
        async let window: Void = followWindow()
        _ = await (boxes, locations, midnight, timeChanges, window)
    }

    func retry() {
        attempt += 1
    }

    private func followBoxes() async {
        do {
            for try await current in environment.boxes.observeCabinet() {
                cabinetBoxes = current
                rebuild()
            }
        } catch {
            fail(error)
        }
    }

    private func followLocations() async {
        do {
            for try await current in environment.locations.observeAll() {
                locations = current
                hasLocations = true
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

    private func followTimeChanges() async {
        for await _ in environment.todaySource.changes() {
            refreshToday()
        }
    }

    private func followWindow() async {
        for await snapshot in environment.preferences.changes() where snapshot.window != window {
            window = snapshot.window
            rebuild()
        }
    }

    private func fail(_ error: any Error) {
        guard !(error is CancellationError) else { return }
        // The failure kind only: never a name or a date of the cabinet.
        Log.persistence.error("Observing the expiring boxes failed: \(String(describing: type(of: error)))")
        phase = .failed
    }

    /// The day changed (midnight, time zone, or the app came back to the foreground): statuses are computed again.
    func refreshToday() {
        let day = environment.today()
        guard day != today else { return }
        today = day
        rebuild()
    }

    // MARK: What the screen shows

    /// The tab badge: boxes that are expired or expiring soon. Unknown boxes do not count.
    var attentionCount: Int { overview?.attentionCount ?? 0 }

    /// The location's name; empty for a location that no longer exists.
    func locationName(_ id: Location.ID) -> String {
        locations.first { $0.id == id }?.name ?? ""
    }

    // MARK: Thrown away

    /// Marks the box thrown away at the clock's time and offers Undo. When the write fails the box stays as it was and
    /// the message says so.
    func throwAway(_ entry: ExpiringEntry) async {
        let boxes = environment.boxes
        let id = entry.box.id
        do {
            try await boxes.markRemoved(id: id, reason: .thrownAway, at: environment.clock.now)
        } catch {
            Log.persistence.error("Throwing a box away failed: \(String(describing: type(of: error)))")
            undo?.show(String(localized: "expiring.thrownAway.failed"))
            return
        }
        let message = CabinetText.undoMessage("expiring.thrownAway.done", name: entry.box.product.name)
        undo?.offerRestore(message) { try await boxes.undoRemoval(id: id) }
    }

    // MARK: Building

    private func rebuild() {
        guard let source = cabinetBoxes, hasLocations else { return }
        build?.cancel()
        let day = today
        let window = window
        build = Task {
            // The core is pure and synchronous; the work stays off the main thread.
            let result = await Task.detached(priority: .userInitiated) {
                ExpiringOverview.make(boxes: source, today: day, window: window)
            }.value
            guard !Task.isCancelled else { return }
            overview = result
            phase = .loaded
        }
    }
}
