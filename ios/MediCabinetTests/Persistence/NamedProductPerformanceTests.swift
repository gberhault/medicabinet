import Foundation
import GRDB
import MediCabinetCore
import Testing
@testable import MediCabinet

/// The Settings list reads the named products and their box counts in one query (S7). Measured about 26 ms.
@Suite struct NamedProductPerformanceTests {
    private static func gtin(_ index: Int) -> String { "0340093" + String(format: "%07d", index) }

    @Test func theSummariesOfAThousandProductsAndBoxesAreReadWithinTheBudget() async throws {
        let (database, directory) = try AppDatabase.temporary()
        defer { try? FileManager.default.removeItem(at: directory) }
        let clock = FixedClock(Date(timeIntervalSince1970: 1_800_000_000))
        let location = try await GRDBLocationRepository(database: database, clock: clock).ensureDefault(named: "Cabinet")
        try await database.writer.write { db in
            for index in 0..<1_000 {
                try NamedProductRecord(NamedProduct(gtin: Self.gtin(index), name: "Produit \(index)", createdAt: clock.now)).insert(db)
                let box = Box(
                    product: ProductRef(gtin: Self.gtin(index), name: "Produit \(index)"), locationID: location.id,
                    serial: "S\(index)", createdAt: clock.now
                )
                try BoxRecord(box).insert(db)
            }
        }
        let named = GRDBNamedProductRepository(database: database, clock: clock)

        var durations: [Duration] = []
        for _ in 0..<5 {
            let started = ContinuousClock.now
            var first: [NamedProductSummary] = []
            for try await summaries in named.observeSummaries() {
                first = summaries
                break
            }
            durations.append(ContinuousClock.now - started)
            #expect(first.count == 1_000 && first.allSatisfy { $0.boxesInCabinet == 1 })
        }
        let median = durations.sorted()[durations.count / 2]
        #expect(median < .milliseconds(150), "median \(median)")
    }
}
