import SwiftUI

struct SettingsView: View {
    var info: any AppInfo = BundleAppInfo()

    var body: some View {
        NavigationStack {
            List {
                NavigationLink {
                    AboutView(model: AboutViewModel(info: info))
                } label: {
                    Label("settings.about", systemImage: "info.circle")
                }
                .accessibilityIdentifier("settings.about")
            }
            .navigationTitle("settings.title")
        }
    }
}

struct AboutView: View {
    let model: AboutViewModel

    var body: some View {
        List {
            Section {
                Text(verbatim: "MediCabinet")
                    .font(.headline)
                Text(verbatim: model.versionLine)
                    .accessibilityIdentifier("about.version")
            }
            Section {
                Text("about.disclaimer")
                    .accessibilityIdentifier("about.disclaimer")
            }
        }
        .navigationTitle("settings.about")
        .navigationBarTitleDisplayMode(.inline)
    }
}
