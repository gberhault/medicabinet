import SwiftUI

struct MainTabView: View {
    let environment: AppEnvironment
    @State private var router: TabRouter
    @State private var undoCenter: UndoCenter
    /// Owned here, not by the Expiring tab, so the tab badge is live whichever tab is selected.
    @State private var expiring: ExpiringViewModel
    @Environment(\.scenePhase) private var scenePhase

    init(environment: AppEnvironment, router: TabRouter = TabRouter()) {
        self.environment = environment
        let undo = UndoCenter()
        _router = State(initialValue: router)
        _undoCenter = State(initialValue: undo)
        _expiring = State(initialValue: ExpiringViewModel(environment: environment, undo: undo))
    }

    var body: some View {
        @Bindable var router = router
        TabView(selection: $router.selection) {
            ScanTab(environment: environment)
                .undoBanner()
                .tabItem { Label("tab.scan", systemImage: "barcode.viewfinder") }
                .tag(AppTab.scan)

            CabinetView(environment: environment, openScan: { router.select(.scan) })
                .undoBanner()
                .tabItem { Label("tab.cabinet", systemImage: "cross.case") }
                .tag(AppTab.cabinet)

            ExpiringView(model: expiring)
                .undoBanner()
                .tabItem {
                    Label("tab.expiring", systemImage: "calendar.badge.exclamationmark")
                        .accessibilityValue(Text(verbatim: expiringBadgeText))
                }
                .badge(expiring.attentionCount)
                .tag(AppTab.expiring)

            SettingsView()
                .undoBanner()
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
                .tag(AppTab.settings)
        }
        .environment(\.undoCenter, undoCenter)
        .animation(.default, value: undoCenter.pending?.id)
        // The badge and the list follow the cabinet from launch, whichever tab is selected; a retry restarts it.
        .task(id: expiring.attempt) { await expiring.observe() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { expiring.refreshToday() }
        }
    }

    /// The badge in words ("3 boxes need attention"); nothing when no box needs attention.
    private var expiringBadgeText: String {
        let count = expiring.attentionCount
        return count > 0 ? ExpiryText.badgeAccessibility(count) : ""
    }
}

/// Owns the scan view model for the life of the tab.
private struct ScanTab: View {
    @State private var model: ScanViewModel

    init(environment: AppEnvironment) {
        let services = ScanServices.live(clock: environment.clock, lookup: environment.reference)
        _model = State(initialValue: ScanViewModel(environment: environment, services: services))
    }

    var body: some View {
        ScanView(model: model)
    }
}
