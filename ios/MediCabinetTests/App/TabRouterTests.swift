import Testing
@testable import MediCabinet

@MainActor
@Suite struct TabRouterTests {
    @Test func startsOnTheScanTab() {
        #expect(TabRouter().selection == .scan)
    }

    @Test func selectingATabChangesTheSelection() {
        let router = TabRouter()
        router.select(.expiring)
        #expect(router.selection == .expiring)
        router.select(.cabinet)
        #expect(router.selection == .cabinet)
    }
}
