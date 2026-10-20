import Foundation
import MediCabinetCore

/// The reference data the app uses: opened lazily on first use (never during launch), once, off the main actor.
/// When the database is missing or unusable every product is unknown and `status()` says why; nothing throws
/// because of it, so the cabinet keeps working.
actor GRDBReferenceData: ReferenceDataSource {
    private let url: URL?
    private var opened: Result<ReferenceDatabase, ReferenceUnavailableReason>?

    init(url: URL?) {
        self.url = url
    }

    func product(gtin: String) async throws -> ReferenceProduct? {
        guard let cip13 = CIP13.from(gtin: gtin), case .success(let database) = open() else { return nil }
        do {
            return try database.product(cip13: cip13)
        } catch {
            // Fails like an unreadable database: the product is unknown, the scan goes on.
            Log.reference.error("reference lookup failed: \(ReferenceUnavailableReason.unreadable.rawValue)")
            return nil
        }
    }

    func status() async -> ReferenceStatus {
        switch open() {
        case .success(let database): .available(dataDate: database.dataDate)
        case .failure(let reason): .unavailable(reason)
        }
    }

    private func open() -> Result<ReferenceDatabase, ReferenceUnavailableReason> {
        if let opened { return opened }
        let result = ReferenceDatabase.open(at: url)
        if case .failure(let reason) = result {
            // The reason only: no path, no product, no code.
            Log.reference.error("reference data unavailable: \(reason.rawValue)")
        }
        opened = result
        return result
    }
}
