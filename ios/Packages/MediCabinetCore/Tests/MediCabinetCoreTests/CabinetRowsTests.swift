import Foundation
import Testing
import MediCabinetCore

@Suite struct CabinetRowsTests {
    private let location = UUID()

    @Test func urgencyRanksExpiredFirstAndOkLastWithUnknownBeforeOk() {
        let ranked = [ExpiryStatus.ok, .unknown, .expiringSoon, .expired].sorted {
            ExpiryUrgency.rank($0) < ExpiryUrgency.rank($1)
        }
        #expect(ranked == [.expired, .expiringSoon, .unknown, .ok])
    }

    @Test func aFilterIsActiveOnlyWhenSomethingNarrowsTheList() {
        #expect(!CabinetFilter().isActive)
        #expect(!CabinetFilter(query: " \u{A0}\t").isActive)
        #expect(CabinetFilter(query: "doli").isActive)
        #expect(CabinetFilter(locationID: location).isActive)
        #expect(CabinetFilter(status: .expired).isActive)
    }

    @Test func theEmptySnapshotHasNothing() {
        #expect(CabinetSnapshot.empty.sections.isEmpty)
        #expect(CabinetSnapshot.empty.boxCount == 0)
    }

    @Test func aBoxWithAnUnknownExpiryIsNeverRankedAsOkEvenAlone() {
        let box = Box(product: ProductRef(gtin: "03400930000014", name: "Doliprane"), locationID: location,
                      createdAt: Date(timeIntervalSince1970: 1))
        let snapshot = CabinetRows.build(boxes: [box], filter: CabinetFilter(), categoryOrder: []) { _ in .unknown }
        #expect(snapshot.sections.first?.rows.first?.status == .unknown)
        #expect(snapshot.sections.first?.rows.first?.earliestExpiry == nil)
    }

    @Test func theStatusRuleIsAskedOncePerBoxThatPassesTheLocationFilter() throws {
        let other = UUID()
        let boxes = (1...4).map { index in
            Box(product: ProductRef(gtin: "0340093000001\(index)", name: "P\(index)"),
                locationID: index.isMultiple(of: 2) ? location : other,
                createdAt: Date(timeIntervalSince1970: TimeInterval(index)))
        }
        nonisolated(unsafe) var calls = 0
        _ = CabinetRows.build(boxes: boxes, filter: CabinetFilter(locationID: location), categoryOrder: []) { _ in
            calls += 1
            return .ok
        }
        #expect(calls == 2)
    }
}

/// Performance budget of design §8: grouping + filtering + search on 1 000 boxes, off the main thread.
@Suite struct CabinetRowsPerformanceTests {
    @Test func buildingTheListOfAThousandBoxesStaysWithinBudget() throws {
        let locations = (0..<4).map { _ in UUID() }
        let categories = (0..<8).map { CategoryID(rawValue: "cat-\($0)") }
        let statuses: [ExpiryStatus] = [.ok, .expiringSoon, .expired, .unknown]
        let boxes = try (0..<1_000).map { index -> Box in
            let product = index % 300
            let expiry = index % 4 == 3 ? nil : try ExpiryDate.make(year: 2026 + index % 4, month: 1 + index % 12, day: nil)
            return Box(
                product: ProductRef(gtin: String(format: "034009300%05d", product), name: "Produit \(product) Éffe"),
                category: categories[product % 8], locationID: locations[index % 4], expiry: expiry,
                createdAt: Date(timeIntervalSince1970: TimeInterval(index)))
        }
        let statusByID = Dictionary(uniqueKeysWithValues: boxes.enumerated().map { ($1.id, statuses[$0 % 4]) })
        let filter = CabinetFilter(query: "produit effe", locationID: locations[0])

        var timings: [Double] = []
        for _ in 0..<10 {
            let clock = ContinuousClock()
            let elapsed = clock.measure {
                _ = CabinetRows.build(boxes: boxes, filter: filter, categoryOrder: categories) { statusByID[$0.id] ?? .unknown }
            }
            timings.append(Double(elapsed.components.seconds) * 1_000 + Double(elapsed.components.attoseconds) / 1e15)
        }
        let median = timings.sorted()[timings.count / 2]
        #expect(median < 100, "median \(median) ms")
    }
}
