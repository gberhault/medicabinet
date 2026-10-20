import Foundation
import UIKit

/// What tells the app that "today" may have changed while it runs: the calendar day rolled over, the time zone changed,
/// or the clock was set. Each emission means "read today again"; the value itself comes from the injected clock.
struct TodaySource: Sendable {
    let changes: @Sendable () -> AsyncStream<Void>

    static let notifications: [Notification.Name] = [
        .NSCalendarDayChanged, .NSSystemTimeZoneDidChange, UIApplication.significantTimeChangeNotification
    ]

    /// The system notifications; `center` is injectable so tests post their own.
    static func live(center: NotificationCenter = .default) -> TodaySource {
        TodaySource {
            let (stream, continuation) = AsyncStream<Void>.makeStream(bufferingPolicy: .bufferingNewest(1))
            let tokens = ObserverTokens(
                notifications.map { name in
                    center.addObserver(forName: name, object: nil, queue: nil) { _ in continuation.yield() }
                })
            continuation.onTermination = { _ in tokens.remove(from: center) }
            return stream
        }
    }

    /// Never emits: the default of tests that do not move the day.
    static let never = TodaySource {
        AsyncStream(unfolding: {
            try? await Task.sleep(for: .seconds(365 * 24 * 3600))
            return nil
        })
    }
}

private final class ObserverTokens: @unchecked Sendable {
    private let tokens: [any NSObjectProtocol]

    init(_ tokens: [any NSObjectProtocol]) {
        self.tokens = tokens
    }

    func remove(from center: NotificationCenter) {
        for token in tokens { center.removeObserver(token) }
    }
}
