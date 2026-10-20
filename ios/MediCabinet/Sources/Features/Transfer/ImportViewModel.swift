import Foundation
import MediCabinetCore
import Observation
import SwiftUI

/// What an import tells the user when it stops (strings.md, "Errors"). The many validation kinds are one message.
enum ImportFailure: Equatable {
    case tooLarge
    case notArchive
    case damaged
    case newerVersion
    case readFailed
    case backupFailed
    case replaceFailed

    /// The message (`transfer.error.*`).
    var messageKey: String {
        switch self {
        case .tooLarge: "transfer.error.tooLarge"
        case .notArchive: "transfer.error.notArchive"
        case .damaged: "transfer.error.damaged"
        case .newerVersion: "transfer.error.newerVersion"
        case .readFailed: "transfer.error.readFailed"
        case .backupFailed: "transfer.error.backupFailed"
        case .replaceFailed: "transfer.error.replaceFailed"
        }
    }

    /// A refused file stays refused; reading, the backup and the replace can be tried again.
    var canRetry: Bool {
        switch self {
        case .readFailed, .backupFailed, .replaceFailed: true
        case .tooLarge, .notArchive, .damaged, .newerVersion: false
        }
    }

    static func from(_ kind: ArchiveErrorKind) -> ImportFailure {
        switch kind {
        case .tooLarge, .tooManyItems: .tooLarge
        case .notJSON, .notArchive: .notArchive
        case .newerVersion: .newerVersion
        case .unsupportedVersion, .invalidStructure, .invalidValue, .duplicateId, .unknownLocation, .defaultLocation,
             .duplicateSerial: .damaged
        }
    }
}

/// Presentation state of one import or restore (I1–I10, B5, design flows 3–8): read the file (capped), check it,
/// show the preview, and only after an explicit confirmation back up and replace the cabinet in one transaction.
@MainActor
@Observable
final class ImportViewModel {
    enum Stage: Equatable {
        case reading
        case checking
        /// `preview` holds what the file and the phone contain; nothing has changed yet.
        case preview
        /// Backing up the current cabinet (when there is something to lose), then replacing it.
        case applying(backingUp: Bool)
        /// `result` holds what was imported.
        case done
        case failed(ImportFailure)
    }

    private(set) var stage: Stage = .reading
    private(set) var preview: ImportPreview?
    private(set) var result: ImportResult?

    let request: ImportCoordinator.Request
    private let transfer: CabinetTransfer
    private let revision: CabinetRevision
    private let limits: ArchiveLimits
    private let announce: @MainActor (String) -> Void
    private var bytes: [UInt8]?

    init(
        request: ImportCoordinator.Request, transfer: CabinetTransfer, revision: CabinetRevision,
        limits: ArchiveLimits = .default,
        announce: @escaping @MainActor (String) -> Void = { AccessibilityNotification.Announcement($0).post() }
    ) {
        self.request = request
        self.transfer = transfer
        self.revision = revision
        self.limits = limits
        self.announce = announce
    }

    /// The backup being restored, `nil` for an import.
    var restoring: ArchiveBackupEntry? {
        if case let .backup(entry) = request.source { return entry }
        return nil
    }

    var isWorking: Bool {
        switch stage {
        case .reading, .checking, .applying: true
        case .preview, .done, .failed: false
        }
    }

    /// Reads and checks the file; nothing is written (V1).
    func start() async {
        stage = .reading
        let bytes: [UInt8]
        do {
            bytes = try await read()
        } catch let error as ArchiveReadError {
            fail(error == .tooLarge ? .tooLarge : .readFailed, stage: "read")
            return
        } catch {
            fail(.readFailed, stage: "read")
            return
        }
        self.bytes = bytes
        await check(bytes)
    }

    /// "Replace" in the confirmation dialog: the backup, then the replace, in one transaction (I3–I6).
    func replace() async {
        guard let preview, stage == .preview || stage == .failed(.backupFailed) || stage == .failed(.replaceFailed) else {
            return
        }
        stage = .applying(backingUp: preview.willBackUp)
        announce(TransferText.string(preview.willBackUp ? "transfer.import.backingUp" : "transfer.import.applying"))
        do {
            let result = try await transfer.apply(preview, reason: request.reason)
            if result.pruneFailed { Log.archive.error("Old automatic backups could not be deleted") }
            self.result = result
            stage = .done
            revision.bump()
            announce(TransferText.string("transfer.import.done"))
        } catch TransferError.backupFailed {
            fail(.backupFailed, stage: "backup")
        } catch {
            fail(.replaceFailed, stage: "replace")
        }
    }

    func retry() async {
        guard case let .failed(failure) = stage, failure.canRetry else { return }
        switch failure {
        case .backupFailed, .replaceFailed:
            if preview != nil {
                await replace()
            } else if let bytes {
                await check(bytes)
            } else {
                await start()
            }
        default:
            await start()
        }
    }

    // MARK: Private

    private func read() async throws -> [UInt8] {
        switch request.source {
        case let .file(url):
            let limit = limits.maxFileBytes
            return try await Task.detached(priority: .userInitiated) { try ArchiveFiles.readCapped(url, limit: limit) }.value
        case let .backup(entry):
            return try await transfer.readBackup(entry.id)
        }
    }

    private func check(_ bytes: [UInt8]) async {
        stage = .checking
        do {
            preview = try await transfer.preview(bytes)
            stage = .preview
        } catch let error as ArchiveError {
            fail(ImportFailure.from(error.kind), stage: "check", kind: error.kind.rawValue)
        } catch {
            fail(.replaceFailed, stage: "check")
        }
    }

    /// Logs the stage and the error kind only, never file contents (V5, X1); VoiceOver hears the message.
    private func fail(_ failure: ImportFailure, stage name: String, kind: String? = nil) {
        Log.archive.error("Import stopped at \(name): \(kind ?? String(describing: failure))")
        stage = .failed(failure)
        announce(TransferText.string(failure.messageKey))
    }
}
