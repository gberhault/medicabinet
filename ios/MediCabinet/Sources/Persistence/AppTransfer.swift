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
}
