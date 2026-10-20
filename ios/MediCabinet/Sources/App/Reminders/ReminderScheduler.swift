import Foundation
import MediCabinetCore

/// Keeps the next monthly digests scheduled with the system, for as long as `run()` is running.
///
/// iOS local notifications carry their text from the moment they are scheduled, so the scheduler computes the next
/// `ExpiryDigest.scheduledAhead` (12) digests in advance, each with its own counts as of its digest day, and writes
/// them again whenever something they depend on changes: the cabinet, the window, the reminder setting, the
/// permission (the app came back), the day or the time zone. Requests are debounced so a bulk scan does not
/// reschedule per box. Everything runs in this actor, never on the main actor.
actor ReminderScheduler {
    /// How long changes are gathered before the digests are written again.
    static let debounce: Duration = .seconds(1)

    private let client: any NotificationClient
    private let boxes: any BoxRepository
    private let preferences: any ExpiryPreferences
    private let clock: any Clock
    private let timeZone: @Sendable () -> TimeZone
    private let todaySource: TodaySource
    private let sleep: @Sendable (Duration) async throws -> Void
    private let bundle: Bundle
    private let locale: Locale

    private var debouncing: Task<Void, Never>?
    private var isRescheduling = false
    private var changedWhileRescheduling = false
    /// How many times the digests were written again (for tests).
    private(set) var rescheduleCount = 0

    /// `sleep` is injected so tests neither wait for the debounce nor for the clock.
    init(
        client: any NotificationClient, boxes: any BoxRepository, preferences: any ExpiryPreferences,
        clock: any Clock, timeZone: @escaping @Sendable () -> TimeZone, todaySource: TodaySource,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        bundle: Bundle = .main, locale: Locale = .current
    ) {
        self.client = client
        self.boxes = boxes
        self.preferences = preferences
        self.clock = clock
        self.timeZone = timeZone
        self.todaySource = todaySource
        self.sleep = sleep
        self.bundle = bundle
        self.locale = locale
    }

    // MARK: Triggers

    /// Follows the cabinet, the preferences and the day until the task is cancelled; each change asks for a
    /// (debounced) reschedule.
    func run() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.followCabinet() }
            group.addTask { await self.followPreferences() }
            group.addTask { await self.followDay() }
        }
        debouncing?.cancel()
        debouncing = nil
    }

    private func followCabinet() async {
        do {
            // Only the signal matters: the boxes themselves are read fresh when the digests are written.
            for try await _ in boxes.observeCabinet() {
                requestReschedule()
            }
        } catch {
            guard !(error is CancellationError) else { return }
            // The failure kind only: never a name or a date of the cabinet.
            Log.reminders.error("Observing the cabinet for the reminder failed: \(String(describing: type(of: error)))")
        }
    }

    private func followPreferences() async {
        for await _ in preferences.changes() {
            requestReschedule()
        }
    }

    private func followDay() async {
        for await _ in todaySource.changes() {
            requestReschedule()
        }
    }

    /// Writes the digests again after the debounce; a request during the wait restarts it. Also called when the app
    /// comes to the foreground (the permission may have changed).
    func requestReschedule() {
        debouncing?.cancel()
        debouncing = Task { [sleep] in
            do { try await sleep(Self.debounce) } catch { return }
            await self.debounceElapsed()
        }
    }

    /// The wait is over and was not replaced: the pass that follows is no longer part of the debounce, so a later
    /// request cannot cancel it half way (it only starts the next wait).
    private func debounceElapsed() async {
        guard !Task.isCancelled else { return }
        debouncing = nil
        await reschedule()
    }

    // MARK: Scheduling

    /// Brings the pending digests in line with the cabinet, the window and the permission. Safe to call at any time:
    /// a call while another is running is folded into one more pass.
    func reschedule() async {
        guard !isRescheduling else {
            changedWhileRescheduling = true
            return
        }
        isRescheduling = true
        repeat {
            changedWhileRescheduling = false
            await writeDigests()
        } while changedWhileRescheduling
        isRescheduling = false
    }

    private func writeDigests() async {
        let authorization = await client.authorization()
        let existing = await client.pendingDigestIDs()
        guard preferences.reminderEnabled, authorization == .authorized else {
            // Off, or the system no longer allows notifications: nothing stays scheduled (N13).
            await client.removePending(ids: existing)
            rescheduleCount += 1
            return
        }
        let cabinet: [Box]
        do {
            cabinet = try await boxes.activeBoxes()
        } catch {
            // The cabinet cannot be read now: what is pending stays until the next change.
            Log.reminders.error("Reading the cabinet for the reminder failed: \(String(describing: type(of: error)))")
            return
        }

        let digests = Self.digests(
            boxes: cabinet, window: preferences.window, now: clock.now, timeZone: timeZone(), bundle: bundle, locale: locale)
        for digest in digests {
            do {
                try await client.schedule(digest)
            } catch {
                Log.reminders.error("Scheduling a digest failed: \(String(describing: type(of: error)))")
            }
        }
        // Replace-then-remove: the previous digests are never all gone while the new ones are being written.
        let keep = Set(digests.map(\.id))
        await client.removePending(ids: existing.filter { !keep.contains($0) })
        rescheduleCount += 1
    }

    /// The digests to schedule: one per upcoming digest day (at most 12) whose counts are not both zero. Counts
    /// are computed as of the digest day and are all the text carries.
    static func digests(
        boxes: [Box], window: ExpiryWindow, now: Date, timeZone: TimeZone, bundle: Bundle = .main,
        locale: Locale = .current
    ) -> [DigestNotification] {
        let today = CalendarDay.from(now, timeZone: timeZone)
        let minutes = CalendarDay.minutesSinceMidnight(of: now, timeZone: timeZone)
        let days = ExpiryDigest.upcoming(today: today, minutes: minutes, count: ExpiryDigest.scheduledAhead)
        return days.compactMap { day in
            guard let counts = ExpiryDigest.counts(boxes: boxes, on: day, window: window),
                  let body = DigestText.body(counts, window: window, bundle: bundle, locale: locale)
            else { return nil }
            return DigestNotification(
                id: NotificationIDs.digest(for: day), day: day, title: DigestText.title(bundle: bundle), body: body)
        }
    }
}
