import Foundation

/// Writes an archive in canonical form (design §2.3). The model is validated first, so the app never
/// produces a file it would refuse to import; locations are ordered by `sortOrder` then id, boxes by
/// `createdAt` then id, so one cabinet always gives the same bytes on both platforms.
public enum ArchiveEncoder {
    public static func encode(_ archive: Archive, limits: ArchiveLimits = .default) throws(ArchiveError) -> [UInt8] {
        try ArchiveValidator.validate(archive, limits: limits)
        var writer = CanonicalJSONWriter()
        writer.beginObject()
        writer.key("archive")
        writer.string(ArchiveFormat.magic)
        writer.key("formatVersion")
        writer.integer(Int64(ArchiveFormat.currentVersion))
        writer.key("exportedAt")
        writer.string(ArchiveDates.format(archive.exportedAt))
        write(archive.app, to: &writer)
        writer.key("locations")
        writer.beginArray()
        for location in archive.cabinet.locations.sorted(by: Self.isOrdered) { write(location, to: &writer) }
        writer.endArray()
        writer.key("boxes")
        writer.beginArray()
        for archived in archive.cabinet.boxes.sorted(by: Self.isOrdered) { write(archived, to: &writer) }
        writer.endArray()
        writer.endObject()
        let bytes = writer.finish()
        guard bytes.count <= limits.maxFileBytes else { throw ArchiveError(.tooLarge) }
        return bytes
    }

    private static func isOrdered(_ lhs: ArchivedLocation, _ rhs: ArchivedLocation) -> Bool {
        let (left, right) = (lhs.location, rhs.location)
        return left.sortOrder != right.sortOrder ? left.sortOrder < right.sortOrder : left.id.uuidString < right.id.uuidString
    }

    private static func isOrdered(_ lhs: ArchivedBox, _ rhs: ArchivedBox) -> Bool {
        let left = ArchiveDates.milliseconds(of: lhs.box.createdAt)
        let right = ArchiveDates.milliseconds(of: rhs.box.createdAt)
        return left != right ? left < right : lhs.box.id.uuidString < rhs.box.id.uuidString
    }

    private static func write(_ app: ArchiveApp, to writer: inout CanonicalJSONWriter) {
        writer.key("app")
        writer.beginObject()
        writer.key("name")
        writer.string(app.name)
        writer.key("platform")
        writer.string(app.platform)
        writer.key("version")
        writer.string(app.version)
        writer.key("build")
        writer.string(app.build)
        writer.endObject()
    }

    private static func write(_ archived: ArchivedLocation, to writer: inout CanonicalJSONWriter) {
        let location = archived.location
        writer.beginObject()
        writer.key("id")
        writer.string(location.id.uuidString)
        writer.key("name")
        writer.string(location.name)
        writer.key("isDefault")
        writer.bool(location.isDefault)
        writer.key("sortOrder")
        writer.integer(Int64(location.sortOrder))
        writer.key("createdAt")
        writer.string(ArchiveDates.format(location.createdAt))
        writer.key("updatedAt")
        writer.string(ArchiveDates.format(location.updatedAt))
        writer.key("deletedAt")
        write(archived.deletedAt, to: &writer)
        writer.endObject()
    }

    private static func write(_ archived: ArchivedBox, to writer: inout CanonicalJSONWriter) {
        let box = archived.box
        writer.beginObject()
        writer.key("id")
        writer.string(box.id.uuidString)
        writer.key("gtin")
        write(box.product.gtin, to: &writer)
        writer.key("productName")
        writer.string(box.product.name)
        writer.key("category")
        writer.string(box.category.rawValue)
        writer.key("categoryOverridden")
        writer.bool(box.categoryOverridden)
        writer.key("locationId")
        writer.string(box.locationID.uuidString)
        writer.key("expiry")
        write(box.expiry, to: &writer)
        writer.key("lot")
        write(box.lot, to: &writer)
        writer.key("serial")
        write(box.serial, to: &writer)
        writer.key("isOpened")
        writer.bool(box.isOpened)
        writer.key("removal")
        write(box.removal, to: &writer)
        writer.key("createdAt")
        writer.string(ArchiveDates.format(box.createdAt))
        writer.key("updatedAt")
        writer.string(ArchiveDates.format(box.updatedAt))
        writer.key("deletedAt")
        write(archived.deletedAt, to: &writer)
        writer.endObject()
    }

    private static func write(_ text: String?, to writer: inout CanonicalJSONWriter) {
        if let text { writer.string(text) } else { writer.null() }
    }

    private static func write(_ date: Date?, to writer: inout CanonicalJSONWriter) {
        if let date { writer.string(ArchiveDates.format(date)) } else { writer.null() }
    }

    private static func write(_ expiry: ExpiryDate?, to writer: inout CanonicalJSONWriter) {
        guard let expiry else { return writer.null() }
        writer.beginObject()
        writer.key("year")
        writer.integer(Int64(expiry.year))
        writer.key("month")
        writer.integer(Int64(expiry.month))
        writer.key("day")
        if let day = expiry.day { writer.integer(Int64(day)) } else { writer.null() }
        writer.endObject()
    }

    private static func write(_ removal: Box.Removal?, to writer: inout CanonicalJSONWriter) {
        guard let removal else { return writer.null() }
        writer.beginObject()
        writer.key("reason")
        writer.string(removal.reason.rawValue)
        writer.key("at")
        writer.string(ArchiveDates.format(removal.date))
        writer.endObject()
    }
}
