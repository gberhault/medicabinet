import Foundation
import MediCabinetCore
import Testing
@testable import MediCabinet

/// The scan path must confirm a box in under 300 ms; the data part (evaluate + add) gets a third of that.
@Suite struct ScanIntakePerformanceTests {
    private struct NoLookup: ProductLookup {
        func product(gtin: String) async throws -> ReferenceProduct? { nil }
    }

    @Test func evaluatingAndAddingABoxOnAThousandBoxCabinetStaysUnderTheBudget() async throws {
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        let clock = FixedClock(Date(timeIntervalSince1970: 1_800_000_000))
        let boxes = GRDBBoxRepository(database: database, clock: clock)
        let location = try await GRDBLocationRepository(database: database, clock: clock).ensureDefault(named: "Cabinet")
        try await database.writer.write { db in
            for index in 0..<1_000 {
                let box = Box(
                    product: ProductRef(gtin: "0340093" + String(format: "%07d", index), name: "Product \(index)"),
                    locationID: location.id, serial: "S\(index)", createdAt: clock.now
                )
                try BoxRecord(box).insert(db)
            }
        }
        let intake = ScanIntake(boxes: boxes, lookup: NoLookup(), clock: clock)

        var durations: [Duration] = []
        for run in 0..<10 {
            let code = ScannedCode(
                gtin: "0340093" + String(format: "%07d", 500 + run), expiry: .missing, lot: "L", serial: "NEW\(run)",
                symbology: .dataMatrix
            )
            let started = ContinuousClock.now
            guard case var .needsCompletion(draft, _, _) = try await intake.evaluate(code, sessionGTINs: []) else {
                Issue.record("expected a completion outcome")
                return
            }
            draft.name = "Name"
            try await intake.add(draft, location: location.id)
            durations.append(ContinuousClock.now - started)
        }
        let median = durations.sorted()[durations.count / 2]
        #expect(median < .milliseconds(100), "median \(median)")
    }
}
