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
            let temporary = ProcessInfo.processInfo.arguments.contains(temporaryStorageArgument)
            if temporary {
                directory = FileManager.default.temporaryDirectory
                    .appendingPathComponent("MediCabinetUITests-\(UUID().uuidString)", isDirectory: true)
            } else {
                directory = try AppDatabase.defaultDirectory()
            }
            let state = await Bootstrap(
                directory: directory,
                defaultLocationName: String(localized: "location.default.name"),
                referenceURL: Bundle.main.url(forResource: "reference", withExtension: "sqlite"),
                // A throwaway launch must neither read nor write the device's real preferences.
                preferences: temporary ? InMemoryExpiryPreferences() : UserDefaultsExpiryPreferences()
            ).run()
            if case .ready(let environment) = state {
                let process = ProcessInfo.processInfo
                await UITestSeed.apply(
                    UITestSeed.items(from: process.environment, arguments: process.arguments), to: environment)
            }
            return state
        } catch {
            return .failed(.storage)
        }
    }
}
