import MediCabinetCore
import Observation
import SwiftUI

/// The one pending "message + Undo" the app offers after a removal or a deletion. A new action replaces the
/// previous one (its undo is no longer offered); the message disappears after `displayDuration` unless it is held.
@MainActor @Observable
final class UndoCenter {
    struct Pending: Identifiable {
        let id = UUID()
        let message: String
        /// `nil` for a plain message with no Undo button (an undo that could not be done).
        let undo: (@MainActor @Sendable () async -> Void)?
    }

    /// Matches Android's long Snackbar.
    static let displayDuration: Duration = .seconds(10)

    private(set) var pending: Pending?
    @ObservationIgnored private var timer: Task<Void, Never>?
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void

    /// `sleep` is injected so tests do not wait ten seconds.
    init(sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.sleep = sleep
    }

    func show(_ message: String, undo: @escaping @MainActor @Sendable () async -> Void) {
        pending = Pending(message: message, undo: undo)
        startTimer()
    }

    /// A message with **Undo** that runs `restore`. A restore that fails says so in a plain message: another box took
    /// the serial number meanwhile, or the write failed. Nothing is logged but the failure kind.
    func offerRestore(_ message: String, restore: @escaping @Sendable () async throws -> Void) {
        show(message) { [weak self] in
            do {
                try await restore()
            } catch RepositoryError.duplicateSerial {
                self?.show(String(localized: "undo.restoreFailed"))
            } catch {
                Log.persistence.error("Undo failed: \(String(describing: type(of: error)))")
                self?.show(String(localized: "box.error"))
            }
        }
    }

    /// A message with no action, for what an Undo could not restore.
    func show(_ message: String) {
        pending = Pending(message: message, undo: nil)
        startTimer()
    }

    /// Runs the pending undo, once. The message goes away first, so a second tap does nothing.
    func perform() async {
        guard let current = pending else { return }
        dismiss()
        await current.undo?()
    }

    func dismiss() {
        timer?.cancel()
        timer = nil
        pending = nil
    }

    /// While assistive technology is on the message it must not vanish.
    func hold() {
        timer?.cancel()
        timer = nil
    }

    func resume() {
        if pending != nil { startTimer() }
    }

    private func startTimer() {
        timer?.cancel()
        guard let id = pending?.id else { return }
        timer = Task { [sleep] in
            do { try await sleep(Self.displayDuration) } catch { return }
            // A timer that was cancelled (held, replaced, dismissed) must never dismiss, whatever its sleep did.
            if !Task.isCancelled, pending?.id == id { pending = nil }
        }
    }
}

private struct UndoCenterKey: EnvironmentKey {
    static let defaultValue: UndoCenter? = nil
}

extension EnvironmentValues {
    /// Set by the main tab view; `nil` only outside it (previews, isolated tests).
    var undoCenter: UndoCenter? {
        get { self[UndoCenterKey.self] }
        set { self[UndoCenterKey.self] = newValue }
    }
}
