import SwiftUI

@main
struct MediCabinetApp: App {
    @State private var model = BootstrapModel(run: AppLaunch.bootstrapRun)

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
        }
    }
}

enum AppLaunch {
    /// UI tests pass this argument so they never touch the real cabinet.
    static let temporaryStorageArgument = "-UITestingTemporaryStorage"

    @Sendable static func bootstrapRun() async -> BootstrapState {
        do {
            let directory: URL
            if ProcessInfo.processInfo.arguments.contains(temporaryStorageArgument) {
                directory = FileManager.default.temporaryDirectory
                    .appendingPathComponent("MediCabinetUITests-\(UUID().uuidString)", isDirectory: true)
            } else {
                directory = try AppDatabase.defaultDirectory()
            }
            return await Bootstrap(
                directory: directory,
                defaultLocationName: String(localized: "location.default.name")
            ).run()
        } catch {
            return .failed(.storage)
        }
    }
}
