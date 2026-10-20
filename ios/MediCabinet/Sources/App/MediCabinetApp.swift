import SwiftUI

@main
struct MediCabinetApp: App {
    @State private var model = BootstrapModel(run: AppLaunch.bootstrapRun)
    @State private var router: TabRouter
    @State private var importer = ImportCoordinator()
    private let notifications = AppLaunch.notificationClient()
    /// Kept for the life of the app: the notification center holds its delegate weakly.
    private let notificationDelegate: ReminderNotificationDelegate

    /// The delegate of the notification center is set here, at launch and before the first scene appears, so a tap on
    /// a digest that launches the app is delivered (N9).
    init() {
        let router = TabRouter()
        _router = State(initialValue: router)
        let delegate = ReminderNotificationDelegate(openExpiring: { router.select(.expiring) })
        delegate.install()
        notificationDelegate = delegate
    }

    var body: some Scene {
        WindowGroup {
            RootView(model: model, router: router, notifications: notifications, importer: importer)
        }
    }
}

enum AppLaunch {
    /// UI tests pass this argument so they never touch the real cabinet.
    static let temporaryStorageArgument = "-UITestingTemporaryStorage"

    /// UI tests: `-UITestNotifications <notDetermined|denied|authorized|refuseOnPrompt>` replaces the system's
    /// notification center with one in memory (no system prompt, nothing posted). Honoured only with
    /// `-UITestingTemporaryStorage`.
    static let notificationsArgument = "-UITestNotifications"

    /// UI tests: `-UITestingOpenArchive <path>` opens that file as if it had been opened from Files (copied into
    /// `Documents/Inbox` first, like iOS does). Honoured only with `-UITestingTemporaryStorage`.
    static let openArchiveArgument = "-UITestingOpenArchive"

    static func uiTestOpenedArchive(arguments: [String] = ProcessInfo.processInfo.arguments) async -> URL? {
        guard arguments.contains(temporaryStorageArgument),
              let index = arguments.firstIndex(of: openArchiveArgument), index + 1 < arguments.count,
              let inbox = ArchiveFiles.live.inboxDirectory
        else { return nil }
        let source = URL(fileURLWithPath: arguments[index + 1])
        return await Task.detached(priority: .userInitiated) { () -> URL? in
            let fileManager = FileManager.default
            let copy = inbox.appendingPathComponent(source.lastPathComponent)
            do {
                try fileManager.createDirectory(at: inbox, withIntermediateDirectories: true)
                if fileManager.fileExists(atPath: copy.path) { try fileManager.removeItem(at: copy) }
                try fileManager.copyItem(at: source, to: copy)
                return copy
            } catch {
                return nil
            }
        }.value
    }

    static func notificationClient(arguments: [String] = ProcessInfo.processInfo.arguments) -> any NotificationClient {
        guard arguments.contains(temporaryStorageArgument),
              let index = arguments.firstIndex(of: notificationsArgument), index + 1 < arguments.count
        else { return LiveNotificationClient() }
        switch arguments[index + 1] {
        case "denied": return InMemoryNotificationClient(status: .denied)
        case "authorized": return InMemoryNotificationClient(status: .authorized)
        case "refuseOnPrompt": return InMemoryNotificationClient(status: .notDetermined, grantsOnRequest: false)
        default: return InMemoryNotificationClient(status: .notDetermined)
        }
    }

    /// Fills the named products from the existing boxes, in the background and off the cold-start path: nothing waits
    /// for it, and a run that cannot finish (reference unavailable, a failure) is retried at the next launch.
    @discardableResult
    static func startBackfill(_ environment: AppEnvironment) -> Task<Void, Never> {
        let runner = environment.namedBackfill
        return Task.detached(priority: .utility) {
            if case .interrupted(let kind) = await runner.run() {
                Log.persistence.error("The named products fill was interrupted: \(kind)")
            }
        }
    }

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
                    UITestSeed.items(from: process.environment, arguments: process.arguments),
                    namedProducts: UITestSeed.namedProducts(from: process.environment, arguments: process.arguments),
                    to: environment)
                startBackfill(environment)
            }
            return state
        } catch {
            return .failed(.storage)
        }
    }
}
