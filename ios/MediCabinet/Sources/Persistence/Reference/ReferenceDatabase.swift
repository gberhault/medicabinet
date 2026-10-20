import Foundation
import GRDB
import MediCabinetCore

/// The bundled reference database (BDPM names, categories and CIS by CIP13), opened read-only. Nothing here writes to it;
/// it holds no household data.
struct ReferenceDatabase: Sendable {
    /// The reference schema version this app understands (`PRAGMA user_version`).
    static let supportedSchemaVersion = 2

    let queue: DatabaseQueue
    let dataDate: ReferenceDataDate

    /// Opens and validates the file: right schema version, intact, with a valid data date. `nil` URL = not bundled.
    static func open(
        at url: URL?,
        fileManager: FileManager = .default
    ) -> Result<ReferenceDatabase, ReferenceUnavailableReason> {
        guard let url, fileManager.fileExists(atPath: url.path) else { return .failure(.missing) }
        do {
            var configuration = Configuration()
            configuration.readonly = true
            let queue = try DatabaseQueue(path: url.path, configuration: configuration)
            return try queue.read { db -> Result<ReferenceDatabase, ReferenceUnavailableReason> in
                guard try Int.fetchOne(db, sql: "PRAGMA user_version") == supportedSchemaVersion else {
                    return .failure(.unsupportedSchema)
                }
                guard try String.fetchOne(db, sql: "PRAGMA quick_check") == "ok",
                      let text = try String.fetchOne(db, sql: "SELECT value FROM meta WHERE key = 'dataDate'"),
                      let dataDate = ReferenceDataDate(iso: text) else {
                    return .failure(.unreadable)
                }
                return .success(ReferenceDatabase(queue: queue, dataDate: dataDate))
            }
        } catch {
            return .failure(.unreadable)
        }
    }

    /// The product of a CIP13 (withdrawn ones included), or `nil` when the database does not know it.
    func product(cip13: String) throws -> ReferenceProduct? {
        try queue.read { db in
            let sql = "SELECT name, category, cis, withdrawnSince FROM presentation WHERE cip13 = ?"
            guard let row = try Row.fetchOne(db, sql: sql, arguments: [cip13]) else { return nil }
            let withdrawnSince: String? = row["withdrawnSince"]
            return ReferenceProduct(
                name: row["name"],
                category: CategoryID(rawValue: row["category"]),
                cis: row["cis"],
                isWithdrawn: withdrawnSince != nil)
        }
    }
}
