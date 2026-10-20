import Foundation

/// The `formatVersion` 2 reader: version 1 plus the root member `namedProducts` (design named-products §7). The members
/// of version 1 are read by the same code, because they did not change. Never edit once shipped: a change is a new
/// version with its own reader (F9).
enum ArchiveV2Reader: ArchiveVersionReader {
    typealias Reading = ArchiveObjectReading

    static func read(_ reader: inout JSONReader, limits: ArchiveLimits) throws(ArchiveError) -> ArchiveBody {
        var fields = ArchiveV1Reader.fields(limits: limits)
        fields.append(ObjectField(key: "namedProducts") { body, reader, field in
            var cursor = try Reading.ListCursor(&reader, path: field.text, limit: limits.maxNamedProducts)
            while let index = try cursor.next(&reader) {
                body.namedProducts.append(try readNamedProduct(&reader, path: "namedProducts[\(index)]"))
            }
        })
        var body = ArchiveBody()
        try Reading.readObject(&reader, path: "", into: &body, fields: fields, opened: true)
        return body
    }

    private struct NamedProductDraft {
        var id = UUID()
        var gtin = ""
        var name = ""
        var category = ""
        var categoryOverridden = false
        var createdAt = Date(timeIntervalSince1970: 0)
        var updatedAt = Date(timeIntervalSince1970: 0)
        var deletedAt: Date?
    }

    private static let namedProductFields: [ObjectField<NamedProductDraft>] = [
        ObjectField(key: "id") { draft, reader, field in draft.id = try Reading.readUUID(&reader, field) },
        ObjectField(key: "gtin") { draft, reader, field in draft.gtin = try reader.readString(field) },
        ObjectField(key: "name") { draft, reader, field in draft.name = try reader.readString(field) },
        ObjectField(key: "category") { draft, reader, field in draft.category = try reader.readString(field) },
        ObjectField(key: "categoryOverridden") { draft, reader, field in
            draft.categoryOverridden = try reader.readBool(field)
        },
        ObjectField(key: "createdAt") { draft, reader, field in draft.createdAt = try Reading.readDate(&reader, field) },
        ObjectField(key: "updatedAt") { draft, reader, field in draft.updatedAt = try Reading.readDate(&reader, field) },
        ObjectField(key: "deletedAt") { draft, reader, field in draft.deletedAt = try Reading.readOptionalDate(&reader, field) }
    ]

    private static func readNamedProduct(_ reader: inout JSONReader, path: String) throws(ArchiveError) -> ArchivedNamedProduct {
        var draft = NamedProductDraft()
        try Reading.readObject(&reader, path: path, into: &draft, fields: namedProductFields)
        let product = NamedProduct(
            id: draft.id, gtin: draft.gtin, name: draft.name, category: CategoryID(rawValue: draft.category),
            categoryOverridden: draft.categoryOverridden, createdAt: draft.createdAt, updatedAt: draft.updatedAt
        )
        return ArchivedNamedProduct(product: product, deletedAt: draft.deletedAt)
    }
}
