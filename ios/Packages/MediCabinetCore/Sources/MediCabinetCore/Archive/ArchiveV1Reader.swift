import Foundation

/// What a reader hands back: the archive without its version.
struct ArchiveBody {
    var exportedAt = Date(timeIntervalSince1970: 0)
    var app = ArchiveApp(name: "", platform: "", version: "", build: "")
    var locations: [ArchivedLocation] = []
    var boxes: [ArchivedBox] = []
    /// Empty for version 1, which has none.
    var namedProducts: [ArchivedNamedProduct] = []
    /// A version 1 file has no named products: the app fills them from the boxes after the import (named products).
    var namedProductsNeedFill = false
}

/// Reads the members of one `formatVersion` after `archive` and `formatVersion`, into the current model.
protocol ArchiveVersionReader {
    static func read(_ reader: inout JSONReader, limits: ArchiveLimits) throws(ArchiveError) -> ArchiveBody
}

/// The `formatVersion` 1 reader (design §2). Never edit once shipped: a change is a new version with
/// its own reader, and migrates into the current model there (F9).
enum ArchiveV1Reader: ArchiveVersionReader {
    typealias Reading = ArchiveObjectReading

    static func read(_ reader: inout JSONReader, limits: ArchiveLimits) throws(ArchiveError) -> ArchiveBody {
        var body = ArchiveBody()
        try Reading.readObject(&reader, path: "", into: &body, fields: fields(limits: limits), opened: true)
        body.namedProductsNeedFill = true
        return body
    }

    /// The members of version 1, which version 2 keeps unchanged (it adds `namedProducts` to them).
    static func fields(limits: ArchiveLimits) -> [ObjectField<ArchiveBody>] {
        [
            ObjectField(key: "exportedAt") { body, reader, field in body.exportedAt = try Reading.readDate(&reader, field) },
            ObjectField(key: "app") { body, reader, field in
                try Reading.readObject(&reader, path: field.text, into: &body.app, fields: appFields)
            },
            ObjectField(key: "locations") { body, reader, field in
                var cursor = try Reading.ListCursor(&reader, path: field.text, limit: limits.maxLocations)
                while let index = try cursor.next(&reader) {
                    body.locations.append(try readLocation(&reader, path: "locations[\(index)]"))
                }
            },
            ObjectField(key: "boxes") { body, reader, field in
                var cursor = try Reading.ListCursor(&reader, path: field.text, limit: limits.maxBoxes)
                while let index = try cursor.next(&reader) {
                    body.boxes.append(try readBox(&reader, path: "boxes[\(index)]"))
                }
            }
        ]
    }

    // MARK: Objects

    private static let appFields: [ObjectField<ArchiveApp>] = [
        ObjectField(key: "name") { app, reader, field in app.name = try reader.readString(field) },
        ObjectField(key: "platform") { app, reader, field in app.platform = try reader.readString(field) },
        ObjectField(key: "version") { app, reader, field in app.version = try reader.readString(field) },
        ObjectField(key: "build") { app, reader, field in app.build = try reader.readString(field) }
    ]

    private struct LocationDraft {
        var id = UUID()
        var name = ""
        var isDefault = false
        var sortOrder = 0
        var createdAt = Date(timeIntervalSince1970: 0)
        var updatedAt = Date(timeIntervalSince1970: 0)
        var deletedAt: Date?
    }

    private static let locationFields: [ObjectField<LocationDraft>] = [
        ObjectField(key: "id") { draft, reader, field in draft.id = try Reading.readUUID(&reader, field) },
        ObjectField(key: "name") { draft, reader, field in draft.name = try reader.readString(field) },
        ObjectField(key: "isDefault") { draft, reader, field in draft.isDefault = try reader.readBool(field) },
        ObjectField(key: "sortOrder") { draft, reader, field in draft.sortOrder = try Reading.readInt32(&reader, field) },
        ObjectField(key: "createdAt") { draft, reader, field in draft.createdAt = try Reading.readDate(&reader, field) },
        ObjectField(key: "updatedAt") { draft, reader, field in draft.updatedAt = try Reading.readDate(&reader, field) },
        ObjectField(key: "deletedAt") { draft, reader, field in draft.deletedAt = try Reading.readOptionalDate(&reader, field) }
    ]

    private static func readLocation(_ reader: inout JSONReader, path: String) throws(ArchiveError) -> ArchivedLocation {
        var draft = LocationDraft()
        try Reading.readObject(&reader, path: path, into: &draft, fields: locationFields)
        let location = Location(
            id: draft.id, name: draft.name, isDefault: draft.isDefault, sortOrder: draft.sortOrder,
            createdAt: draft.createdAt, updatedAt: draft.updatedAt
        )
        return ArchivedLocation(location: location, deletedAt: draft.deletedAt)
    }

    private struct BoxDraft {
        var id = UUID()
        var gtin: String?
        var productName = ""
        var category = ""
        var categoryOverridden = false
        var locationID = UUID()
        var expiry: ExpiryDate?
        var lot: String?
        var serial: String?
        var isOpened = false
        var removal: Box.Removal?
        var createdAt = Date(timeIntervalSince1970: 0)
        var updatedAt = Date(timeIntervalSince1970: 0)
        var deletedAt: Date?
    }

    private static let boxFields: [ObjectField<BoxDraft>] = [
        ObjectField(key: "id") { draft, reader, field in draft.id = try Reading.readUUID(&reader, field) },
        ObjectField(key: "gtin") { draft, reader, field in draft.gtin = try reader.readOptionalString(field) },
        ObjectField(key: "productName") { draft, reader, field in draft.productName = try reader.readString(field) },
        ObjectField(key: "category") { draft, reader, field in draft.category = try reader.readString(field) },
        ObjectField(key: "categoryOverridden") { draft, reader, field in
            draft.categoryOverridden = try reader.readBool(field)
        },
        ObjectField(key: "locationId") { draft, reader, field in draft.locationID = try Reading.readUUID(&reader, field) },
        ObjectField(key: "expiry") { draft, reader, field in draft.expiry = try readExpiry(&reader, field) },
        ObjectField(key: "lot") { draft, reader, field in draft.lot = try reader.readOptionalString(field) },
        ObjectField(key: "serial") { draft, reader, field in draft.serial = try reader.readOptionalString(field) },
        ObjectField(key: "isOpened") { draft, reader, field in draft.isOpened = try reader.readBool(field) },
        ObjectField(key: "removal") { draft, reader, field in draft.removal = try readRemoval(&reader, field) },
        ObjectField(key: "createdAt") { draft, reader, field in draft.createdAt = try Reading.readDate(&reader, field) },
        ObjectField(key: "updatedAt") { draft, reader, field in draft.updatedAt = try Reading.readDate(&reader, field) },
        ObjectField(key: "deletedAt") { draft, reader, field in draft.deletedAt = try Reading.readOptionalDate(&reader, field) }
    ]

    private static func readBox(_ reader: inout JSONReader, path: String) throws(ArchiveError) -> ArchivedBox {
        var draft = BoxDraft()
        try Reading.readObject(&reader, path: path, into: &draft, fields: boxFields)
        let box = Box(
            id: draft.id, product: ProductRef(gtin: draft.gtin, name: draft.productName),
            category: CategoryID(rawValue: draft.category), categoryOverridden: draft.categoryOverridden,
            locationID: draft.locationID, expiry: draft.expiry, lot: draft.lot, serial: draft.serial,
            isOpened: draft.isOpened, createdAt: draft.createdAt, updatedAt: draft.updatedAt, removal: draft.removal
        )
        return ArchivedBox(box: box, deletedAt: draft.deletedAt)
    }

    // MARK: Expiry and removal

    private struct ExpiryDraft {
        var year: Int64 = 0
        var month: Int64 = 0
        var day: Int64?
    }

    private static let expiryFields: [ObjectField<ExpiryDraft>] = [
        ObjectField(key: "year") { draft, reader, field in draft.year = try reader.readInteger(field) },
        ObjectField(key: "month") { draft, reader, field in draft.month = try reader.readInteger(field) },
        ObjectField(key: "day") { draft, reader, field in
            draft.day = try reader.readNullIfPresent() ? nil : try reader.readInteger(field)
        }
    ]

    /// `null` is an unknown expiry. A date `ExpiryDate.make` rejects makes the file invalid: it is never
    /// clamped, corrected or turned into "unknown" (V3).
    private static func readExpiry(_ reader: inout JSONReader, _ field: FieldPath) throws(ArchiveError) -> ExpiryDate? {
        if try reader.readNullIfPresent() { return nil }
        var draft = ExpiryDraft()
        try Reading.readObject(&reader, path: field.text, into: &draft, fields: expiryFields)
        guard let year = Int(exactly: draft.year), let month = Int(exactly: draft.month),
              let expiry = try? ExpiryDate.make(year: year, month: month, day: draft.day.flatMap { Int(exactly: $0) })
        else { throw ArchiveError(.invalidValue, path: field.text) }
        return expiry
    }

    private struct RemovalDraft {
        var reason = RemovalReason.usedUp
        var date = Date(timeIntervalSince1970: 0)
    }

    private static let removalFields: [ObjectField<RemovalDraft>] = [
        ObjectField(key: "reason") { draft, reader, field in
            guard let reason = RemovalReason(rawValue: try reader.readString(field)) else {
                throw ArchiveError(.invalidValue, path: field.text)
            }
            draft.reason = reason
        },
        ObjectField(key: "at") { draft, reader, field in draft.date = try Reading.readDate(&reader, field) }
    ]

    private static func readRemoval(_ reader: inout JSONReader, _ field: FieldPath) throws(ArchiveError) -> Box.Removal? {
        if try reader.readNullIfPresent() { return nil }
        var draft = RemovalDraft()
        try Reading.readObject(&reader, path: field.text, into: &draft, fields: removalFields)
        return Box.Removal(reason: draft.reason, date: draft.date)
    }
}
