import Foundation

/// Decides which sightings of a code count as a new scan, so a box held in front of the camera
/// counts once while the next box (or the same box shown again after a pause) counts again.
///
/// A payload is new when it was never seen, or was last seen `rescanGapMilliseconds` or more
/// before. Every sighting refreshes the last-seen time, so only a real absence re-arms a code.
public struct ScanDebouncer: Sendable {
    public static let defaultRescanGapMilliseconds = 1_000
    /// Entries older than this many gaps are forgotten (bounded memory); they would count as new anyway.
    private static let evictionFactor: Int64 = 10

    private let rescanGap: Int64
    private var lastSeen: [String: Int64] = [:]

    public init(rescanGapMilliseconds: Int = defaultRescanGapMilliseconds) {
        rescanGap = Int64(rescanGapMilliseconds)
    }

    /// Feed every frame; returns the payloads that count as new scans, in the order they appear in `visible`.
    public mutating func observe(visible: [String], at instant: Date) -> [String] {
        let now = Int64((instant.timeIntervalSince1970 * 1000).rounded())
        var scans: [String] = []
        var handled = Set<String>()
        for payload in visible where handled.insert(payload).inserted {
            let isNew = lastSeen[payload].map { now - $0 >= rescanGap } ?? true
            if isNew { scans.append(payload) }
            lastSeen[payload] = now
        }
        lastSeen = lastSeen.filter { now - $0.value <= rescanGap * Self.evictionFactor }
        return scans
    }
}
