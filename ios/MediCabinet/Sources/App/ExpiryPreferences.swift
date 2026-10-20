import Foundation
import MediCabinetCore
import os

/// The three expiry preferences at one moment: what `ExpiryPreferences.changes()` emits.
struct ExpiryPreferencesSnapshot: Equatable, Sendable {
    var window: ExpiryWindow = .default
    /// The user's choice to receive the monthly digest (the system authorisation is separate).
    var reminderEnabled = false
    /// "Not now" was chosen on the Expiring tab's reminder card.
    var cardDismissed = false
}

/// Device preferences of expiry tracking: the "expiring soon" window and the reminder choices. Not cabinet data: never
/// exported, never part of the cabinet backup. Safe to read from any thread (the status rules run off the main
/// thread).
protocol ExpiryPreferences: AnyObject, Sendable {
    /// A missing or unrecognised stored value reads as the default window.
    var window: ExpiryWindow { get set }
    var reminderEnabled: Bool { get set }
    var cardDismissed: Bool { get set }
    var snapshot: ExpiryPreferencesSnapshot { get }
    /// The current preferences now, then again after every change (latest wins).
    func changes() -> AsyncStream<ExpiryPreferencesSnapshot>
}

/// Fans a snapshot out to every subscriber of `changes()`.
final class ExpiryPreferenceBroadcaster: Sendable {
    private let continuations = OSAllocatedUnfairLock(
        initialState: [UUID: AsyncStream<ExpiryPreferencesSnapshot>.Continuation]())

    /// `current` is read after the subscription is registered, so no change can slip in between.
    func stream(current: @Sendable () -> ExpiryPreferencesSnapshot) -> AsyncStream<ExpiryPreferencesSnapshot> {
        let (stream, continuation) = AsyncStream<ExpiryPreferencesSnapshot>.makeStream(bufferingPolicy: .bufferingNewest(1))
        let id = UUID()
        continuations.withLock { $0[id] = continuation }
        continuation.onTermination = { [continuations] _ in
            continuations.withLock { $0[id] = nil }
        }
        continuation.yield(current())
        return stream
    }

    func publish(_ snapshot: ExpiryPreferencesSnapshot) {
        continuations.withLock { for continuation in $0.values { continuation.yield(snapshot) } }
    }
}

final class UserDefaultsExpiryPreferences: ExpiryPreferences, @unchecked Sendable {
    static let windowKey = "expiry.windowDays"
    static let reminderEnabledKey = "reminder.enabled"
    static let cardDismissedKey = "reminder.cardDismissed"

    private let defaults: UserDefaults
    private let broadcaster = ExpiryPreferenceBroadcaster()

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var window: ExpiryWindow {
        get { ExpiryWindow(storedDays: defaults.object(forKey: Self.windowKey) as? Int) }
        set {
            defaults.set(newValue.days, forKey: Self.windowKey)
            broadcaster.publish(snapshot)
        }
    }

    var reminderEnabled: Bool {
        get { defaults.bool(forKey: Self.reminderEnabledKey) }
        set {
            defaults.set(newValue, forKey: Self.reminderEnabledKey)
            broadcaster.publish(snapshot)
        }
    }

    var cardDismissed: Bool {
        get { defaults.bool(forKey: Self.cardDismissedKey) }
        set {
            defaults.set(newValue, forKey: Self.cardDismissedKey)
            broadcaster.publish(snapshot)
        }
    }

    var snapshot: ExpiryPreferencesSnapshot {
        ExpiryPreferencesSnapshot(window: window, reminderEnabled: reminderEnabled, cardDismissed: cardDismissed)
    }

    func changes() -> AsyncStream<ExpiryPreferencesSnapshot> {
        broadcaster.stream { [self] in snapshot }
    }
}

final class InMemoryExpiryPreferences: ExpiryPreferences, Sendable {
    private let state: OSAllocatedUnfairLock<ExpiryPreferencesSnapshot>
    private let broadcaster = ExpiryPreferenceBroadcaster()

    init(_ initial: ExpiryPreferencesSnapshot = ExpiryPreferencesSnapshot()) {
        state = OSAllocatedUnfairLock(initialState: initial)
    }

    var window: ExpiryWindow {
        get { state.withLock { $0.window } }
        set { update { $0.window = newValue } }
    }

    var reminderEnabled: Bool {
        get { state.withLock { $0.reminderEnabled } }
        set { update { $0.reminderEnabled = newValue } }
    }

    var cardDismissed: Bool {
        get { state.withLock { $0.cardDismissed } }
        set { update { $0.cardDismissed = newValue } }
    }

    var snapshot: ExpiryPreferencesSnapshot { state.withLock { $0 } }

    func changes() -> AsyncStream<ExpiryPreferencesSnapshot> {
        broadcaster.stream { [self] in snapshot }
    }

    private func update(_ change: @Sendable (inout ExpiryPreferencesSnapshot) -> Void) {
        let updated = state.withLock { value -> ExpiryPreferencesSnapshot in
            change(&value)
            return value
        }
        broadcaster.publish(updated)
    }
}
