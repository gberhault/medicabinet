import Foundation
import MediCabinetCore

/// Why a picked or opened file could not be read.
enum ArchiveReadError: Error, Equatable {
    /// Larger than an archive can be: refused before reading it (V2).
    case tooLarge
    /// The file could not be opened or read.
    case unreadable
}

/// The files of export and import outside the database (design §1.4, §6): the temporary export file in `tmp/Export`,
/// the copies iOS makes in `Documents/Inbox` of files opened with the app, and the capped reading of a picked or
/// opened file. They hold health data, so they are deleted as soon as their flow ends and again at every launch.
/// Every call does file I/O: never call it on the main actor.
struct ArchiveFiles: Sendable {
    /// `tmp/Export`: excluded from device backups, emptied at every launch and every new export.
    let exportDirectory: URL
    /// `Documents/Inbox`, where iOS copies a file opened with the app (`LSSupportsOpeningDocumentsInPlace = NO`).
    let inboxDirectory: URL?

    static var live: ArchiveFiles {
        let fileManager = FileManager.default
        return ArchiveFiles(
            exportDirectory: fileManager.temporaryDirectory.appendingPathComponent("Export", isDirectory: true),
            inboxDirectory: fileManager.urls(for: .documentDirectory, in: .userDomainMask).first?
                .appendingPathComponent("Inbox", isDirectory: true)
        )
    }

    // MARK: Export

    /// Writes the export as `tmp/Export/<name>`, readable only while the phone is unlocked. An earlier export
    /// still there is deleted first.
    func writeExport(_ bytes: [UInt8], named name: String) throws -> URL {
        let fileManager = FileManager.default
        if fileManager.fileExists(atPath: exportDirectory.path) { try fileManager.removeItem(at: exportDirectory) }
        try fileManager.createDirectory(at: exportDirectory, withIntermediateDirectories: true)
        let url = exportDirectory.appendingPathComponent(name)
        try Data(bytes).write(to: url, options: [.atomic, .completeFileProtection])
        return url
    }

    /// Deletes a temporary export file (the share sheet finished, whatever the outcome).
    func deleteExport(_ url: URL) {
        guard isInside(url, exportDirectory) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    // MARK: Opened files

    /// True for a copy iOS made of a file opened with the app: it belongs to the app and is deleted after use.
    func isInInbox(_ url: URL) -> Bool {
        guard let inboxDirectory else { return false }
        return isInside(url, inboxDirectory)
    }

    /// Deletes an opened file's copy once its import flow ended. Files elsewhere (picked in Files) are never touched.
    func discardIfInInbox(_ url: URL) {
        guard isInInbox(url) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    /// Launch cleanup: empties `tmp/Export` and `Documents/Inbox`, except files a flow is still waiting to read.
    func cleanUp(keeping kept: Set<URL> = []) {
        let fileManager = FileManager.default
        try? fileManager.removeItem(at: exportDirectory)
        guard let inboxDirectory,
              let names = try? fileManager.contentsOfDirectory(atPath: inboxDirectory.path)
        else { return }
        let keptPaths = Set(kept.map(Self.resolvedPath))
        for name in names {
            let url = inboxDirectory.appendingPathComponent(name)
            if !keptPaths.contains(Self.resolvedPath(url)) { try? fileManager.removeItem(at: url) }
        }
    }

    // MARK: Reading

    /// Reads a picked or opened file, refusing it unread when it is larger than `limit` bytes. Picker URLs are
    /// security-scoped; the size is checked before reading, and reading stops at `limit + 1` bytes in case the file
    /// grows meanwhile.
    static func readCapped(_ url: URL, limit: Int) throws(ArchiveReadError) -> [UInt8] {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data: Data
        do {
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            guard try handle.seekToEnd() <= UInt64(limit) else { throw ArchiveReadError.tooLarge }
            try handle.seek(toOffset: 0)
            data = try handle.read(upToCount: limit + 1) ?? Data()
        } catch ArchiveReadError.tooLarge {
            throw .tooLarge
        } catch {
            throw .unreadable
        }
        guard data.count <= limit else { throw .tooLarge }
        return [UInt8](data)
    }

    private func isInside(_ url: URL, _ directory: URL) -> Bool {
        let folder = Self.resolvedPath(directory)
        return Self.resolvedPath(url).hasPrefix(folder.hasSuffix("/") ? folder : folder + "/")
    }

    /// `/var/…` and `/private/var/…` name the same file.
    private static func resolvedPath(_ url: URL) -> String {
        url.standardizedFileURL.resolvingSymlinksInPath().path
    }
}
