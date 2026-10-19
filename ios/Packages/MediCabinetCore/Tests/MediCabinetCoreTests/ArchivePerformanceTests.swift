import Foundation
import Testing
import MediCabinetCoreTestSupport
@testable import MediCabinetCore

/// Timing budgets (design §9) for a 1 000-box cabinet, asserted on the median of several runs so one slow
/// run does not fail them. They are at least 3× what the unoptimised test build measures (about 150 ms to
/// decode, 50 ms to encode; a release build is many times faster), a quarter of the 2 s export/import target.
@Suite struct ArchivePerformanceTests {
    private func median(runs: Int = 5, _ work: () throws -> Void) rethrows -> Duration {
        var times: [Duration] = []
        for _ in 0..<runs {
            let clock = ContinuousClock()
            times.append(try clock.measure(work))
        }
        return times.sorted()[times.count / 2]
    }

    @Test func encodingAThousandBoxesStaysWithinBudget() throws {
        let archive = try ArchiveDecoder.decode(Array(SyntheticArchive.json(locations: 3, boxes: 1_000).utf8))
        let time = try median { _ = try ArchiveEncoder.encode(archive) }
        #expect(time < .milliseconds(500), "encoding took \(time)")
    }

    @Test func decodingAThousandBoxesStaysWithinBudget() throws {
        let bytes = Array(SyntheticArchive.json(locations: 3, boxes: 1_000).utf8)
        let time = try median { _ = try ArchiveDecoder.decode(bytes) }
        #expect(time < .milliseconds(500), "decoding took \(time)")
    }

    @Test func decodingTheLargestAllowedCabinetFinishesInTime() throws {
        let limits = ArchiveLimits.default
        let bytes = Array(SyntheticArchive.json(locations: limits.maxLocations, boxes: limits.maxBoxes).utf8)
        #expect(bytes.count < limits.maxFileBytes)
        let time = try median(runs: 1) { _ = try ArchiveDecoder.decode(bytes) }
        #expect(time < .seconds(10), "decoding took \(time)")
    }
}
