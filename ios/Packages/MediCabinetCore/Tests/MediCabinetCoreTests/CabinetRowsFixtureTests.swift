import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/cabinet-rows.json`, which the Android core also runs.
@Suite struct CabinetRowsFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.cabinetRows()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 25)
    }

    @Test(arguments: SharedFixtures.cabinetRowsCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: CabinetRowsCase) throws {
        var locations: [String: UUID] = [:]
        func location(_ label: String) throws -> UUID {
            if let known = locations[label] { return known }
            let made = try #require(UUID(uuidString: String(format: "10000000-0000-0000-0000-%012d", locations.count + 1)))
            locations[label] = made
            return made
        }

        var statuses: [Box.ID: ExpiryStatus] = [:]
        var boxes: [Box] = []
        for fixtureBox in fixtureCase.boxes {
            let expiry = try fixtureBox.expiry.map {
                try ExpiryDate.make(year: $0.year, month: $0.month, day: $0.day)
            }
            let box = Box(
                id: try boxID(fixtureBox.id),
                product: ProductRef(gtin: fixtureBox.gtin, name: fixtureBox.name),
                category: CategoryID(rawValue: fixtureBox.category),
                locationID: try location(fixtureBox.location),
                expiry: expiry,
                createdAt: Date(timeIntervalSince1970: TimeInterval(fixtureBox.createdOrder)))
            statuses[box.id] = try #require(ExpiryStatus(rawValue: fixtureBox.status))
            boxes.append(box)
        }

        let filter = CabinetFilter(
            query: fixtureCase.filter.query,
            locationID: try fixtureCase.filter.location.map(location),
            status: try fixtureCase.filter.status.map { try #require(ExpiryStatus(rawValue: $0)) })
        let snapshot = CabinetRows.build(
            boxes: boxes, filter: filter,
            categoryOrder: fixtureCase.categoryOrder.map { CategoryID(rawValue: $0) },
            status: { statuses[$0.id] ?? .unknown })

        let actual = snapshot.sections.map { section in
            CabinetRowsCase.FixtureSection(
                category: section.category.rawValue,
                rows: section.rows.map { row in
                    CabinetRowsCase.FixtureRow(
                        name: row.name,
                        displayName: row.displayName,
                        boxes: row.boxes.map { label(of: $0.id) },
                        status: row.status.rawValue,
                        earliestExpiry: row.earliestExpiry.map {
                            CabinetRowsCase.FixtureExpiry(year: $0.year, month: $0.month, day: $0.day)
                        })
                })
        }
        #expect(actual == fixtureCase.expect.sections)
        #expect(snapshot.boxCount == fixtureCase.expect.boxCount)
    }
}

/// `bN` → UUID ending in N, so id order is numeric (the fixture's tie-break rule).
private func boxID(_ label: String) throws -> UUID {
    let number = try #require(Int(label.dropFirst()))
    return try #require(UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", number)))
}

private func label(of id: UUID) -> String {
    "b" + String(Int(id.uuidString.suffix(12)) ?? -1)
}
