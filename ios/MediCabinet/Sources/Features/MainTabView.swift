import SwiftUI

struct MainTabView: View {
    let environment: AppEnvironment
    @State private var router: TabRouter
    @State private var undoCenter: UndoCenter
    /// Owned here, not by the Expiring tab, so the tab badge is live whichever tab is selected.
    @State private var expiring: ExpiringViewModel
    @State private var reminder: ReminderModel
    @Environment(\.scenePhase) private var scenePhase

    init(environment: AppEnvironment, router: TabRouter = TabRouter(), notifications: any NotificationClient) {
        self.environment = environment
        let undo = UndoCenter()
        _router = State(initialValue: router)
        _undoCenter = State(initialValue: undo)
        _expiring = State(initialValue: ExpiringViewModel(environment: environment, undo: undo))
        _reminder = State(initialValue: ReminderModel(
            preferences: environment.preferences, client: notifications, openSystemSettings: Self.openSystemSettings))
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

            ExpiringView(model: expiring, reminder: reminder)
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
        .environment(\.reminderModel, reminder)
        .animation(.default, value: undoCenter.pending?.id)
        // The badge and the list follow the cabinet from launch, whichever tab is selected; a retry restarts it.
        .task(id: expiring.attempt) { await expiring.observe() }
        // The permission can be changed in the system settings: read it at launch and whenever the app is back.
        .task { await reminder.refresh() }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            expiring.refreshToday()
            Task { await reminder.refresh() }
        }
    }

    @MainActor private static func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
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
