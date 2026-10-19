import Foundation
import GRDB
import MediCabinetCore

struct LocationRecord: Codable, FetchableRecord, PersistableRecord, Sendable {
    static let databaseTableName = "location"

    var id: String
    var name: String
    var isDefault: Bool
    var sortOrder: Int
    var createdAt: Date
    var updatedAt: Date

    init(_ location: Location) {
        id = location.id.uuidString
        name = location.name
        isDefault = location.isDefault
        sortOrder = location.sortOrder
        createdAt = location.createdAt
        updatedAt = location.updatedAt
    }

    func model() throws -> Location {
        guard let uuid = UUID(uuidString: id) else { throw PersistenceError.corruptRow(table: "location") }
        return Location(
            id: uuid, name: name, isDefault: isDefault, sortOrder: sortOrder,
            createdAt: createdAt, updatedAt: updatedAt
        )
    }
}

struct BoxRecord: Codable, FetchableRecord, PersistableRecord, Sendable {
    static let databaseTableName = "box"

    var id: String
    var gtin: String?
    var productName: String
    var category: String
    var categoryOverridden: Bool
    var locationId: String
    var expiryYear: Int?
    var expiryMonth: Int?
    var expiryDay: Int?
    var expirySortKey: String?
    var lot: String?
    var serial: String?
    var isOpened: Bool
    var removalReason: String?
    var removedAt: Date?
    var createdAt: Date
    var updatedAt: Date

    init(_ box: Box) {
        id = box.id.uuidString
        gtin = box.product.gtin
        productName = box.product.name
        category = box.category.rawValue
        categoryOverridden = box.categoryOverridden
        locationId = box.locationID.uuidString
        expiryYear = box.expiry?.year
        expiryMonth = box.expiry?.month
        expiryDay = box.expiry?.day
        expirySortKey = box.expiry?.sortKey
        lot = box.lot
        serial = box.serial
        isOpened = box.isOpened
        removalReason = box.removal?.reason.rawValue
        removedAt = box.removal?.date
        createdAt = box.createdAt
        updatedAt = box.updatedAt
    }

    func model() throws -> Box {
        guard let uuid = UUID(uuidString: id), let location = UUID(uuidString: locationId) else {
            throw PersistenceError.corruptRow(table: "box")
        }
        return Box(
            id: uuid,
            product: ProductRef(gtin: gtin, name: productName),
            category: CategoryID(rawValue: category),
            categoryOverridden: categoryOverridden,
            locationID: location,
            expiry: try expiry(),
            lot: lot,
            serial: serial,
            isOpened: isOpened,
            createdAt: createdAt,
            updatedAt: updatedAt,
            removal: try removal()
        )
    }

    private func expiry() throws -> ExpiryDate? {
        guard let expiryYear, let expiryMonth else { return nil }
        do {
            return try ExpiryDate.make(year: expiryYear, month: expiryMonth, day: expiryDay)
        } catch {
            throw PersistenceError.corruptRow(table: "box")
        }
    }

    private func removal() throws -> Box.Removal? {
        guard let removalReason, let removedAt else { return nil }
        guard let reason = RemovalReason(rawValue: removalReason) else { throw PersistenceError.corruptRow(table: "box") }
        return Box.Removal(reason: reason, date: removedAt)
    }
}

enum PersistenceError: Error, Equatable {
    /// A stored row cannot be turned back into a domain value. Never fixed up silently.
    case corruptRow(table: String)
}
