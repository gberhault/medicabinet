import SwiftUI

struct MainTabView: View {
    let environment: AppEnvironment
    @State private var undoCenter = UndoCenter()
    @State private var selection = Tab.scan

    enum Tab: Hashable {
        case scan, cabinet, expiring, settings
    }

    var body: some View {
        TabView(selection: $selection) {
            ScanTab(environment: environment)
                .undoBanner()
                .tabItem { Label("tab.scan", systemImage: "barcode.viewfinder") }
                .tag(Tab.scan)

            CabinetView(environment: environment, openScan: { selection = .scan })
                .undoBanner()
                .tabItem { Label("tab.cabinet", systemImage: "cross.case") }
                .tag(Tab.cabinet)

            FeaturePlaceholderView(
                title: "placeholder.expiring.title", message: "placeholder.expiring.message",
                systemImage: "calendar.badge.exclamationmark"
            )
            .undoBanner()
            .tabItem { Label("tab.expiring", systemImage: "calendar.badge.exclamationmark") }
            .tag(Tab.expiring)

            SettingsView()
                .undoBanner()
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .environment(\.undoCenter, undoCenter)
        .animation(.default, value: undoCenter.pending?.id)
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
