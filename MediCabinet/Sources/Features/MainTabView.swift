import SwiftUI

struct MainTabView: View {
    var body: some View {
        TabView {
            FeaturePlaceholderView(
                title: "placeholder.scan.title", message: "placeholder.scan.message", systemImage: "barcode.viewfinder"
            )
            .tabItem { Label("tab.scan", systemImage: "barcode.viewfinder") }

            FeaturePlaceholderView(
                title: "placeholder.cabinet.title", message: "placeholder.cabinet.message", systemImage: "cross.case"
            )
            .tabItem { Label("tab.cabinet", systemImage: "cross.case") }

            FeaturePlaceholderView(
                title: "placeholder.expiring.title", message: "placeholder.expiring.message",
                systemImage: "calendar.badge.exclamationmark"
            )
            .tabItem { Label("tab.expiring", systemImage: "calendar.badge.exclamationmark") }

            SettingsPlaceholder()
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
        }
    }
}

/// Replaced by the real Settings screen in T14.
private struct SettingsPlaceholder: View {
    var body: some View {
        Text("tab.settings")
    }
}
