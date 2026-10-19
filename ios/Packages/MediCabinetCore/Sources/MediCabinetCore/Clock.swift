import Foundation

/// The only source of "now" for domain code, so date logic is testable.
public protocol Clock: Sendable {
    var now: Date { get }
}

public struct SystemClock: Clock {
    public init() {}

    public var now: Date {
        // The one place that reads the system time.
        // swiftlint:disable:next core_no_wall_clock
        Date()
    }
}

public struct FixedClock: Clock {
    public private(set) var now: Date

    public init(_ now: Date) {
        self.now = now
    }

    public mutating func advance(by interval: TimeInterval) {
        now = now.addingTimeInterval(interval)
    }
}
