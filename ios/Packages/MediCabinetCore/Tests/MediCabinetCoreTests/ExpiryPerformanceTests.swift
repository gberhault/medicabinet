import Foundation
import Testing
import MediCabinetCore

/// Timing budget for a 1 000-box cabinet: the Expiring view and twelve digests together must stay far
/// below a frame. The budget is generous (several times the measured cost) and uses the median of 10 runs.
@Suite struct ExpiryPerformanceTests {
    private func cabinet(boxes count: Int) throws -> [Box] {
        let location = UUID()
        let moment = Date(timeIntervalSince1970: 0)
        let names = ["Doliprane", "Éfferalgan", "Smecta", "Spasfon", "Advil", "Nurofen", "Zyrtec", "Gaviscon"]
        return try (0..<count).map { index in
            let expiry: ExpiryDate? = index % 10 == 0
                ? nil
                : try ExpiryDate.make(year: 2026 + index % 4, month: 1 + index % 12, day: index % 3 == 0 ? nil : 1 + index % 28)
            return Box(
                product: ProductRef(name: "\(names[index % names.count]) \(index)"),
                locationID: location,
                expiry: expiry,
                createdAt: moment,
                removal: index % 25 == 0 ? Box.Removal(reason: .usedUp, date: moment) : nil
            )
        }
    }

    @Test func overviewAndTwelveDigestsOnAThousandBoxesStayUnderBudget() throws {
        let boxes = try cabinet(boxes: 1_000)
        let today = try CalendarDay.make(year: 2027, month: 3, day: 15)
        let budget: Duration = .milliseconds(50)
        var timings: [Duration] = []
        for _ in 0..<10 {
            let clock = ContinuousClock()
            let elapsed = clock.measure {
                let overview = ExpiringOverview.make(boxes: boxes, today: today, window: .days60)
                var day = today
                for _ in 0..<ExpiryDigest.scheduledAhead {
                    day = day.firstOfNextMonth()
                    _ = ExpiryDigest.counts(boxes: boxes, on: day, window: .days60)
                }
                #expect(!overview.isEmpty)
            }
            timings.append(elapsed)
        }
        let median = timings.sorted()[timings.count / 2]
        #expect(median < budget, "median \(median) exceeds \(budget)")
    }
}
