import Foundation
import Testing
import MediCabinetCoreTestSupport
@testable import MediCabinetCore

/// Timing budgets (design §9) for a 1 000-box cabinet, asserted on the best of several runs: a busy machine slows
/// some runs but not all, and the best one is what the code costs. The budgets are at least 3× what the unoptimised
/// test build measures when idle (about 150 ms to decode, 50 ms to encode; a release build is many times faster),
/// and a quarter of the 2 s export/import target.
@Suite struct ArchivePerformanceTests {
    private func best(runs: Int = 5, _ work: () throws -> Void) rethrows -> Duration {
        let clock = ContinuousClock()
        var times: [Duration] = []
        for _ in 0..<runs {
            times.append(try clock.measure(work))
        }
        return times.min() ?? .zero
    }

    @Test func encodingAThousandBoxesStaysWithinBudget() throws {
        let archive = try ArchiveDecoder.decode(Array(SyntheticArchive.json(locations: 3, boxes: 1_000).utf8))
        let time = try best { _ = try ArchiveEncoder.encode(archive) }
        #expect(time < .milliseconds(500), "encoding took \(time)")
    }

    @Test func decodingAThousandBoxesStaysWithinBudget() throws {
        let bytes = Array(SyntheticArchive.json(locations: 3, boxes: 1_000).utf8)
        let time = try best { _ = try ArchiveDecoder.decode(bytes) }
        #expect(time < .milliseconds(500), "decoding took \(time)")
    }

    /// A guard against quadratic behaviour only: the largest allowed cabinet takes seconds in a debug build, a
    /// quadratic decoder would take minutes. Hence a budget well above the measure, and a single run.
    @Test func decodingTheLargestAllowedCabinetFinishesInTime() throws {
        let limits = ArchiveLimits.default
        let bytes = Array(SyntheticArchive.json(locations: limits.maxLocations, boxes: limits.maxBoxes).utf8)
        #expect(bytes.count < limits.maxFileBytes)
        let time = try best(runs: 1) { _ = try ArchiveDecoder.decode(bytes) }
        #expect(time < .seconds(60), "decoding took \(time)")
    }
}
