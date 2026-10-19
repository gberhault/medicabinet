import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of the repository-wide `fixtures/scan-debounce.json`, which the Android core also runs.
@Suite struct ScanDebouncerFixtureTests {
    private static let start = Date(timeIntervalSince1970: 1_000_000)

    @Test func theSharedFixtureLoadsAndHasCases() {
        #expect(SharedFixtures.scanDebounceCases().count >= 15)
    }

    @Test(arguments: SharedFixtures.scanDebounceCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: ScanDebounceCase) {
        var debouncer = ScanDebouncer(rescanGapMilliseconds: fixtureCase.rescanGapMs)
        var scans: [ScanDebounceScan] = []
        for frame in fixtureCase.frames {
            let instant = Self.start.addingTimeInterval(Double(frame.milliseconds) / 1000)
            for payload in debouncer.observe(visible: frame.visible, at: instant) {
                scans.append(.init(milliseconds: frame.milliseconds, payload: payload))
            }
        }
        #expect(scans == fixtureCase.expectScans)
    }

    @Test func theDefaultGapIsOneSecond() {
        #expect(ScanDebouncer.defaultRescanGapMilliseconds == 1_000)
        var debouncer = ScanDebouncer()
        #expect(debouncer.observe(visible: ["A"], at: Self.start) == ["A"])
        #expect(debouncer.observe(visible: ["A"], at: Self.start.addingTimeInterval(0.999)).isEmpty)
        #expect(debouncer.observe(visible: ["A"], at: Self.start.addingTimeInterval(0.999 + 1.0)) == ["A"])
    }

    @Test func aClockGoingBackwardsNeverRecounts() {
        var debouncer = ScanDebouncer()
        _ = debouncer.observe(visible: ["A"], at: Self.start)
        #expect(debouncer.observe(visible: ["A"], at: Self.start.addingTimeInterval(-5)).isEmpty)
    }

    @Test func manyFramesOfManyPayloadsStayFast() {
        var debouncer = ScanDebouncer()
        let payloads = (0..<5).map { "payload-\($0)" }
        let clock = ContinuousClock()
        let elapsed = clock.measure {
            for frame in 0..<10_000 {
                _ = debouncer.observe(visible: payloads, at: Self.start.addingTimeInterval(Double(frame) * 0.033))
            }
        }
        #expect(elapsed < .milliseconds(500), "10 000 frames took \(elapsed)")
    }
}
