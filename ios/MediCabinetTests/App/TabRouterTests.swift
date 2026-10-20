import Testing
@testable import MediCabinet

@MainActor
@Suite struct TabRouterTests {
    // Issue #53: the app opens on the cabinet; the camera only starts once the Scan tab is shown.
    @Test func startsOnTheCabinetTab() {
        #expect(TabRouter().selection == .cabinet)
    }

    @Test func aRouterCreatedForATabStartsThere() {
        #expect(TabRouter(selection: .expiring).selection == .expiring)
    }

    @Test func selectingATabChangesTheSelection() {
        let router = TabRouter()
        router.select(.expiring)
        #expect(router.selection == .expiring)
        router.select(.cabinet)
        #expect(router.selection == .cabinet)
    }
}
