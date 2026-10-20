import Foundation
import Testing
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/product-memory.json`, which the Android core also runs.
@Suite struct MemoryPolicyFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.productMemory()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 20)
    }

    private static func box(_ fixture: MemoryCase.FixtureBox) -> Box {
        Box(
            product: ProductRef(gtin: fixture.gtin, name: fixture.name), category: CategoryID(rawValue: fixture.category),
            categoryOverridden: fixture.categoryOverridden, locationID: UUID(), createdAt: Date(timeIntervalSince1970: 0)
        )
    }

    @Test(arguments: SharedFixtures.productMemoryCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: MemoryCase) throws {
        let after = Self.box(fixtureCase.event.after)
        let before = fixtureCase.event.before.map(Self.box)
        let event: MemoryEvent
        switch fixtureCase.event.kind {
        case "added": event = .added(after)
        case "changed": event = .changed(before: try #require(before), after: after)
        case "sessionCorrected": event = .sessionCorrected(before: try #require(before), after: after)
        default: throw FixtureError(description: "unknown event kind \(fixtureCase.event.kind)")
        }
        let current = fixtureCase.current.map {
            NamedProduct(
                gtin: after.product.gtin ?? "03400930000014", name: $0.name, category: CategoryID(rawValue: $0.category),
                categoryOverridden: $0.categoryOverridden, createdAt: Date(timeIntervalSince1970: 0)
            )
        }
        let decision = MemoryPolicy.decide(event, codeKnown: fixtureCase.codeKnown, current: current)
        switch fixtureCase.expect.action {
        case "none":
            #expect(decision == .none)
        case "remember":
            let expected = MemoryDecision.remember(
                name: try #require(fixtureCase.expect.name), category: CategoryID(rawValue: try #require(fixtureCase.expect.category)),
                overridden: try #require(fixtureCase.expect.categoryOverridden)
            )
            #expect(decision == expected)
        default:
            throw FixtureError(description: "unknown action \(fixtureCase.expect.action)")
        }
    }

    private struct FixtureError: Error, CustomStringConvertible {
        let description: String
    }
}
