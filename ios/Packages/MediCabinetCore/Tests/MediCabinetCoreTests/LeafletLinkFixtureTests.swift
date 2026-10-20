import Testing
import Foundation
import MediCabinetCore
import MediCabinetCoreTestSupport

/// Runs every case of `fixtures/leaflet-links.json`, which the Android core also runs.
@Suite struct LeafletLinkFixtureTests {
    @Test func theSharedFixtureLoadsAndHasCases() throws {
        let fixture = try SharedFixtures.leafletLinks()
        #expect(fixture.formatVersion == 1)
        #expect(fixture.cases.count >= 15)
    }

    @Test(arguments: SharedFixtures.leafletLinkCases())
    func caseMatchesTheSharedExpectation(_ fixtureCase: LeafletLinkCase) {
        let product = fixtureCase.product.map {
            ReferenceProduct(name: "X", cis: $0.cis, isWithdrawn: $0.isWithdrawn)
        }
        #expect(LeafletLink.address(for: product) == fixtureCase.expect)
    }
}

@Suite struct LeafletLinkTests {
    @Test func anAddressIsAlwaysAnAbsoluteHTTPSURLOnTheBDPMHost() throws {
        let address = try #require(LeafletLink.address(for: ReferenceProduct(name: "X", cis: "60234100")))
        let url = try #require(URL(string: address))
        #expect(url.scheme == "https")
        #expect(url.host == LeafletLink.host)
        #expect(url.path == "/medicament/60234100/extrait")
    }

    @Test func aProductBuiltWithoutACISGivesNoLink() {
        #expect(LeafletLink.address(for: ReferenceProduct(name: "X")) == nil)
    }
}
