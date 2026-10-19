import SwiftUI

struct MainTabView: View {
    let environment: AppEnvironment
    @State private var undoCenter = UndoCenter()

    var body: some View {
        TabView {
            ScanTab(environment: environment)
                .undoBanner()
                .tabItem { Label("tab.scan", systemImage: "barcode.viewfinder") }

            FeaturePlaceholderView(
                title: "placeholder.cabinet.title", message: "placeholder.cabinet.message", systemImage: "cross.case"
            )
            .undoBanner()
            .tabItem { Label("tab.cabinet", systemImage: "cross.case") }

            FeaturePlaceholderView(
                title: "placeholder.expiring.title", message: "placeholder.expiring.message",
                systemImage: "calendar.badge.exclamationmark"
            )
            .undoBanner()
            .tabItem { Label("tab.expiring", systemImage: "calendar.badge.exclamationmark") }

            SettingsView()
                .undoBanner()
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
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
