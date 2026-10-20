import Foundation
import MediCabinetCore

/// Builds the export / import use case on the cabinet database and the automatic backup folder.
enum AppTransfer {
    static func make(database: AppDatabase, backupsDirectory: URL, clock: any Clock, app: ArchiveApp) -> CabinetTransfer {
        CabinetTransfer(
            store: GRDBCabinetArchiveStore(database: database),
            backups: FileArchiveBackupStore(directory: backupsDirectory),
            clock: clock,
            app: app
        )
    }

    /// This app as the author of an archive (F3): name, platform, version and build from the bundle.
    static func app(info: any AppInfo) -> ArchiveApp {
        ArchiveApp(name: "MediCabinet", platform: "ios", version: info.version, build: info.build)
    }
}
