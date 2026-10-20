import Foundation
import MediCabinetCore

enum ArchiveBackupFileError: Error, Equatable {
    /// The id is not the name of an automatic backup file of this folder.
    case unknownBackup
}

/// The automatic backups taken before an import or a restore (design §1.3, §5.2): archives in
/// `Application Support/MediCabinet/Backups`, next to the pre-migration database copies, protected like the database
/// and included in device backups. The date and the reason are read from the file name, so no file-date API is used.
///
/// `@unchecked Sendable`: the only mutable-looking member is the `FileManager`, whose file operations are thread-safe;
/// tests inject a subclass that records the protection classes set.
struct FileArchiveBackupStore: ArchiveBackupStore, @unchecked Sendable {
    static let fileExtension = ArchiveFormat.fileExtension
    static let partialSuffix = ".partial"
    /// The same class as the database: readable after the first unlock, encrypted at rest until then.
    static let fileProtection = AppDatabase.fileProtection

    /// `Application Support/MediCabinet/Backups`.
    let directory: URL
    var fileManager: FileManager = .default
    var limits: ArchiveLimits = .default

    static func prefix(for reason: BackupReason) -> String {
        switch reason {
        case .beforeImport: "pre-import-"
        case .beforeRestore: "pre-restore-"
        }
    }

    // MARK: ArchiveBackupStore

    /// Writes `<name>.partial`, reads it back and decodes it, then renames it: a file with the final name is always
    /// a complete archive. Any failure removes the partial file and throws.
    func write(_ bytes: [UInt8], reason: BackupReason, takenAt: Date) throws -> ArchiveBackupEntry {
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        try AppDatabase.protect(directory, fileManager: fileManager)
        let name = uniqueName(reason: reason, takenAt: takenAt)
        let final = directory.appendingPathComponent(name)
        let partial = directory.appendingPathComponent(name + Self.partialSuffix)
        do {
            try Data(bytes).write(to: partial, options: [.withoutOverwriting, .completeFileProtectionUntilFirstUserAuthentication])
            try AppDatabase.protect(partial, fileManager: fileManager)
            let archive = try ArchiveDecoder.decode([UInt8](try Data(contentsOf: partial)), limits: limits)
            try fileManager.moveItem(at: partial, to: final)
            return ArchiveBackupEntry(id: name, takenAt: takenAt, reason: reason, summary: archive.cabinet.summary)
        } catch {
            try? fileManager.removeItem(at: partial)
            throw error
        }
    }

    /// Newest first. A file that no longer decodes is listed with no summary, never hidden (B4).
    func list() async throws -> [ArchiveBackupEntry] {
        try entries().map { entry in
            let summary = (try? decoded(entry.name))?.cabinet.summary
            return ArchiveBackupEntry(id: entry.name, takenAt: entry.takenAt, reason: entry.reason, summary: summary)
        }
    }

    func read(_ id: String) async throws -> [UInt8] {
        guard Self.parse(id) != nil else { throw ArchiveBackupFileError.unknownBackup }
        let url = directory.appendingPathComponent(id)
        guard fileManager.fileExists(atPath: url.path) else { throw ArchiveBackupFileError.unknownBackup }
        return [UInt8](try Data(contentsOf: url))
    }

    /// Keeps the `count` newest automatic backups, both reasons counted together, and removes partial files left by
    /// an interrupted write. The pre-migration copies in the same folder are never touched.
    func prune(keeping count: Int) async throws {
        for entry in try entries().dropFirst(max(count, 0)) {
            try fileManager.removeItem(at: directory.appendingPathComponent(entry.name))
        }
        for name in try names() where name.hasSuffix(Self.partialSuffix) {
            let base = String(name.dropLast(Self.partialSuffix.count))
            if Self.parse(base) != nil { try fileManager.removeItem(at: directory.appendingPathComponent(name)) }
        }
    }

    // MARK: Names

    struct NamedEntry: Equatable {
        let name: String
        let takenAt: Date
        let reason: BackupReason
    }

    /// `pre-import-20261019-081530-123.medicabinet` (UTC, milliseconds), with `-2`, `-3`… if that name is taken.
    static func fileName(reason: BackupReason, takenAt: Date, counter: Int = 1) -> String {
        // Whole milliseconds, like the archive dates: the name reads back as the same instant at that precision.
        let total = Int64((takenAt.timeIntervalSince1970 * 1_000).rounded())
        let (seconds, milliseconds) = (total.quotientAndRemainder(dividingBy: 1_000))
        let whole = Date(timeIntervalSince1970: Double(milliseconds < 0 ? seconds - 1 : seconds))
        let parts = utc.dateComponents([.year, .month, .day, .hour, .minute, .second], from: whole)
        let stamp = String(
            format: "%04d%02d%02d-%02d%02d%02d-%03d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0,
            parts.hour ?? 0, parts.minute ?? 0, parts.second ?? 0, Int((milliseconds + 1_000) % 1_000)
        )
        let suffix = counter > 1 ? "-\(counter)" : ""
        return "\(prefix(for: reason))\(stamp)\(suffix).\(fileExtension)"
    }

    /// The reason and date of an automatic backup file name; `nil` for any other file.
    static func parse(_ name: String) -> NamedEntry? {
        guard name.hasSuffix("." + fileExtension), !name.contains("/") else { return nil }
        guard let reason = BackupReason.allCases.first(where: { name.hasPrefix(prefix(for: $0)) }) else { return nil }
        let body = name.dropFirst(prefix(for: reason).count).dropLast(fileExtension.count + 1)
        let fields = body.split(separator: "-", omittingEmptySubsequences: false)
        guard fields.count == 3 || fields.count == 4,
              fields[0].count == 8, fields[1].count == 6, fields[2].count == 3,
              fields.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) })
        else { return nil }
        let date = String(fields[0]), time = String(fields[1])
        func number(_ text: String, _ start: Int, _ length: Int) -> Int? {
            let from = text.index(text.startIndex, offsetBy: start)
            return Int(text[from..<text.index(from, offsetBy: length)])
        }
        var components = DateComponents()
        components.year = number(date, 0, 4)
        components.month = number(date, 4, 2)
        components.day = number(date, 6, 2)
        components.hour = number(time, 0, 2)
        components.minute = number(time, 2, 2)
        components.second = number(time, 4, 2)
        guard let milliseconds = Int(fields[2]), components.isValidDate(in: utc), let base = utc.date(from: components)
        else { return nil }
        return NamedEntry(name: name, takenAt: base.addingTimeInterval(Double(milliseconds) / 1_000), reason: reason)
    }

    private static let utc: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .gmt
        return calendar
    }()

    // MARK: Files

    private func names() throws -> [String] {
        guard fileManager.fileExists(atPath: directory.path) else { return [] }
        return try fileManager.contentsOfDirectory(atPath: directory.path)
    }

    /// The automatic backup files, newest first (same date: the later name first).
    private func entries() throws -> [NamedEntry] {
        try names().compactMap(Self.parse).sorted { ($0.takenAt, $0.name) > ($1.takenAt, $1.name) }
    }

    private func decoded(_ name: String) throws -> Archive {
        let data = try Data(contentsOf: directory.appendingPathComponent(name))
        return try ArchiveDecoder.decode([UInt8](data), limits: limits)
    }

    private func uniqueName(reason: BackupReason, takenAt: Date) -> String {
        var counter = 1
        while true {
            let name = Self.fileName(reason: reason, takenAt: takenAt, counter: counter)
            let taken = [name, name + Self.partialSuffix].contains {
                fileManager.fileExists(atPath: directory.appendingPathComponent($0).path)
            }
            if !taken { return name }
            counter += 1
        }
    }
}
