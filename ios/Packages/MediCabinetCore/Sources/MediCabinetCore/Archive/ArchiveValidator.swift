import Foundation

/// The rules an archive must satisfy, run on the decoded model after reading and on the model
/// before writing (so the app never produces a file it would refuse). Order and error attribution:
/// design §2.7. Paths name the field and never carry a value.
enum ArchiveValidator {
    static func validate(_ archive: Archive, limits: ArchiveLimits) throws(ArchiveError) {
        let cabinet = archive.cabinet
        guard cabinet.locations.count <= limits.maxLocations else { throw ArchiveError(.tooManyItems, path: "locations") }
        guard cabinet.boxes.count <= limits.maxBoxes else { throw ArchiveError(.tooManyItems, path: "boxes") }
        try require(isRepresentable(archive.exportedAt), FieldPath("exportedAt"))
        try validateApp(archive.app)
        for (index, location) in cabinet.locations.enumerated() {
            try validateLocation(location, prefix: "locations[\(index)]")
        }
        for (index, archived) in cabinet.boxes.enumerated() {
            try validateBox(archived, prefix: "boxes[\(index)]")
        }
        try validateReferences(cabinet)
    }

    // MARK: Fields

    private static func validateApp(_ app: ArchiveApp) throws(ArchiveError) {
        try require(isLength(app.name, 1...50), FieldPath("app", "name"))
        try require(ArchiveApp.platforms.contains(app.platform), FieldPath("app", "platform"))
        try require(isLength(app.version, 1...20), FieldPath("app", "version"))
        try require(isLength(app.build, 1...20), FieldPath("app", "build"))
    }

    private static func validateLocation(_ location: Location, prefix: String) throws(ArchiveError) {
        try require(isLength(location.name, 1...100), FieldPath(prefix, "name"))
        try require((Int(Int32.min)...Int(Int32.max)).contains(location.sortOrder), FieldPath(prefix, "sortOrder"))
        try require(isRepresentable(location.createdAt), FieldPath(prefix, "createdAt"))
        try require(isRepresentable(location.updatedAt), FieldPath(prefix, "updatedAt"))
    }

    private static func validateBox(_ archived: ArchivedBox, prefix: String) throws(ArchiveError) {
        let box = archived.box
        try require(isGTIN(box.product.gtin), FieldPath(prefix, "gtin"))
        try require(isLength(box.product.name, 1...200), FieldPath(prefix, "productName"))
        try require(isLength(box.category.rawValue, 1...100), FieldPath(prefix, "category"))
        try require(isLength(box.lot, 1...20), FieldPath(prefix, "lot"))
        try require(isLength(box.serial, 1...20), FieldPath(prefix, "serial"))
        try require(isRepresentable(box.removal?.date), FieldPath(prefix + ".removal", "at"))
        try require(isRepresentable(box.createdAt), FieldPath(prefix, "createdAt"))
        try require(isRepresentable(box.updatedAt), FieldPath(prefix, "updatedAt"))
        try require(isRepresentable(archived.deletedAt), FieldPath(prefix, "deletedAt"))
    }

    private static func require(_ condition: Bool, _ field: FieldPath) throws(ArchiveError) {
        guard condition else { throw ArchiveError(.invalidValue, path: field.text) }
    }

    private static func isLength(_ text: String?, _ range: ClosedRange<Int>) -> Bool {
        text.map { range.contains(codePointCount($0)) } ?? true
    }

    private static func isRepresentable(_ date: Date?) -> Bool {
        date.map(ArchiveDates.isRepresentable) ?? true
    }

    /// Absent, or exactly 14 ASCII digits (the database's rule; the check digit is not verified).
    private static func isGTIN(_ gtin: String?) -> Bool {
        gtin.map { $0.utf8.count == 14 && $0.utf8.allSatisfy { (0x30...0x39).contains($0) } } ?? true
    }

    // MARK: Cross checks

    private static func validateReferences(_ cabinet: CabinetSnapshot) throws(ArchiveError) {
        try requireUnique(cabinet.locations.map(\.id), in: "locations")
        try requireUnique(cabinet.boxes.map(\.box.id), in: "boxes")
        guard cabinet.locations.filter(\.isDefault).count == 1 else {
            throw ArchiveError(.defaultLocation, path: "locations")
        }
        let known = Set(cabinet.locations.map(\.id))
        if let index = cabinet.boxes.firstIndex(where: { !known.contains($0.box.locationID) }) {
            throw ArchiveError(.unknownLocation, path: "boxes[\(index)].locationId")
        }
        try requireUniqueSerials(cabinet.boxes)
    }

    private static func requireUnique(_ ids: [UUID], in list: String) throws(ArchiveError) {
        var seen = Set<UUID>()
        for (index, id) in ids.enumerated() where !seen.insert(id).inserted {
            throw ArchiveError(.duplicateId, path: "\(list)[\(index)].id")
        }
    }

    private struct SerialKey: Hashable {
        let gtin: String
        let serial: String
    }

    /// Among boxes that are not deleted, a (GTIN, serial) pair appears once, like the database's unique
    /// index. A box without a GTIN is never compared (SQL treats NULLs as distinct).
    private static func requireUniqueSerials(_ boxes: [ArchivedBox]) throws(ArchiveError) {
        var seen = Set<SerialKey>()
        for (index, archived) in boxes.enumerated() where archived.deletedAt == nil {
            guard let gtin = archived.box.product.gtin, let serial = archived.box.serial else { continue }
            if !seen.insert(SerialKey(gtin: gtin, serial: serial)).inserted {
                throw ArchiveError(.duplicateSerial, path: "boxes[\(index)].serial")
            }
        }
    }
}
