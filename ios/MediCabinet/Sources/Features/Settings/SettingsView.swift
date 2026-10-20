import SwiftUI

/// Settings → Locations once the cabinet is open (the environment is set after bootstrap).
struct LocationsEntry: View {
    @Environment(\.appEnvironment) private var environment
    @Environment(\.undoCenter) private var undo

    var body: some View {
        if let environment {
            LocationsView(repository: environment.locations, undo: undo)
        } else {
            ProgressView()
        }
    }
}

struct SettingsView: View {
    var info: any AppInfo = BundleAppInfo()
    @Environment(\.appEnvironment) private var environment
    @Environment(\.reminderModel) private var reminder

    var body: some View {
        NavigationStack {
            List {
                NavigationLink {
                    LocationsEntry()
                } label: {
                    Label("settings.locations", systemImage: "mappin.and.ellipse")
                }
                .accessibilityIdentifier("settings.locations")
                if let environment {
                    ExpirySettingsSection(preferences: environment.preferences)
                }
                if let reminder {
                    ReminderSettingsSection(model: reminder)
                }
                // After the everyday settings, which earlier screens and tests expect at the top.
                if let environment, let transfer = environment.transfer {
                    TransferSection(environment: environment, transfer: transfer)
                }
                NavigationLink {
                    AboutView(model: AboutViewModel(info: info))
                } label: {
                    Label("settings.about", systemImage: "info.circle")
                }
                .accessibilityIdentifier("settings.about")
                #if DEBUG
                NavigationLink {
                    PayloadCaptureView()
                } label: {
                    Label { Text(verbatim: "Debug: payload shapes") } icon: { Image(systemName: "ladybug") }
                }
                #endif
            }
            .navigationTitle("settings.title")
        }
    }
}

struct AboutView: View {
    /// Where the data comes from (the licence asks for the source to be cited).
    static let referenceAddress = "base-donnees-publique.medicaments.gouv.fr"

    let model: AboutViewModel
    @Environment(\.appEnvironment) private var environment
    @State private var reference = ReferenceAboutModel()

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
            referenceSection
        }
        .navigationTitle("settings.about")
        .navigationBarTitleDisplayMode(.inline)
        .task { await reference.load(from: environment?.reference) }
    }

    private var referenceSection: some View {
        Section {
            Text("about.reference.source")
            if let url = URL(string: "https://\(Self.referenceAddress)") {
                Link(destination: url) {
                    Text(verbatim: Self.referenceAddress)
                }
                .frame(minHeight: 44)
                .accessibilityHint(Text("about.reference.open"))
                .accessibilityIdentifier("about.reference.link")
            }
            referenceState
            Text("about.reference.notEndorsed")
                .accessibilityIdentifier("about.reference.notEndorsed")
        } header: {
            Text("about.reference.title")
        }
    }

    @ViewBuilder private var referenceState: some View {
        switch reference.state {
        case .loading:
            Label("about.reference.loading", systemImage: "hourglass")
                .accessibilityIdentifier("about.reference.loading")
        case .available:
            Label {
                Text(verbatim: reference.dateLine() ?? "")
            } icon: {
                Image(systemName: "calendar")
            }
            .accessibilityIdentifier("about.reference.date")
        case .unavailable:
            Label("about.reference.unavailable", systemImage: "exclamationmark.triangle")
                .accessibilityIdentifier("about.reference.unavailable")
        }
    }
}
