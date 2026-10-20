import Foundation

/// One case of `fixtures/archive-cases.json`, the archive decode vectors shared with the Android app.
public struct ArchiveCase: Decodable, Sendable, CustomStringConvertible {
    public struct Generate: Decodable, Sendable {
        /// The file is this many spaces (a size-limit case).
        public let fileBytes: Int?
        /// A synthetic valid archive with these counts.
        public let locations: Int?
        public let boxes: Int?
    }

    public struct ExpectedError: Decodable, Sendable {
        public let kind: String
        public let path: String?
    }

    public struct Expect: Decodable, Sendable {
        public let summary: ArchiveCaseSummary?
        public let needsBackup: Bool?
        public let error: ExpectedError?
    }

    public let name: String
    public let file: String?
    public let content: String?
    public let contentBase64: String?
    public let generate: Generate?
    public let expect: Expect

    public var description: String { name }

    /// The bytes this case feeds the decoder.
    public func bytes() throws -> [UInt8] {
        if let file { return [UInt8](try SharedFixtures.data(file)) }
        if let content { return Array(content.utf8) }
        if let contentBase64 { return [UInt8](Data(base64Encoded: contentBase64) ?? Data()) }
        if let generate { return ArchiveCase.generated(generate) }
        return []
    }

    private static func generated(_ spec: Generate) -> [UInt8] {
        if let size = spec.fileBytes { return [UInt8](repeating: 0x20, count: size) }
        return Array(SyntheticArchive.json(locations: spec.locations ?? 1, boxes: spec.boxes ?? 0).utf8)
    }
}

public struct ArchiveCaseSummary: Decodable, Sendable {
    public struct App: Decodable, Sendable {
        public let name: String
        public let platform: String
        public let version: String
        public let build: String
    }

    public let formatVersion: Int
    public let sourceFormatVersion: Int
    public let exportedAt: String
    public let app: App
    public let locations: Int
    public let inCabinet: Int
    public let removed: Int
    public let deleted: Int
    public let unknownExpiry: Int
}

public struct ArchiveCaseFixture: Decodable, Sendable {
    public let formatVersion: Int
    public let description: String
    public let cases: [ArchiveCase]
}

/// A valid archive of a given size, written as plain text (one location is the default; every box is
/// in it). For limit and performance tests.
public enum SyntheticArchive {
    public static func json(locations: Int, boxes: Int) -> String {
        var text = "{\"archive\":\"medicabinet\",\"formatVersion\":1,\"exportedAt\":\"2026-10-19T08:15:30.123Z\","
        text += "\"app\":{\"name\":\"MediCabinet\",\"platform\":\"ios\",\"version\":\"0.3.0\",\"build\":\"3\"},\"locations\":["
        text += (0..<locations).map { location($0) }.joined(separator: ",")
        text += "],\"boxes\":["
        text += (0..<boxes).map { box($0) }.joined(separator: ",")
        return text + "]}"
    }

    public static func uuid(_ prefix: String, _ index: Int) -> String {
        prefix + String(format: "%07X", index) + "-0000-4000-8000-" + String(format: "%012X", index)
    }

    private static func location(_ index: Int) -> String {
        "{\"id\":\"\(uuid("1", index))\",\"name\":\"Location \(index)\",\"isDefault\":\(index == 0),\"sortOrder\":\(index),"
            + "\"createdAt\":\"2026-10-01T09:00:00.000Z\",\"updatedAt\":\"2026-10-01T09:00:00.000Z\",\"deletedAt\":null}"
    }

    private static func box(_ index: Int) -> String {
        "{\"id\":\"\(uuid("B", index))\",\"gtin\":\"03400936404564\",\"productName\":\"Box \(index)\",\"category\":\"uncategorized\","
            + "\"categoryOverridden\":false,\"locationId\":\"\(uuid("1", 0))\","
            + "\"expiry\":{\"year\":2027,\"month\":3,\"day\":15},\"lot\":\"LOT\(index % 1000)\",\"serial\":\"SN\(index)\","
            + "\"isOpened\":false,\"removal\":null,\"createdAt\":\"2026-10-02T10:00:00.000Z\","
            + "\"updatedAt\":\"2026-10-02T10:00:00.000Z\",\"deletedAt\":null}"
    }
}

extension SharedFixtures {
    public static func archiveCaseFixture() throws -> ArchiveCaseFixture {
        try JSONDecoder().decode(ArchiveCaseFixture.self, from: data("archive-cases.json"))
    }

    /// For `@Test(arguments:)`, which cannot throw. A missing file yields no cases; the suite also has a
    /// test asserting the file loads.
    public static func archiveCases() -> [ArchiveCase] {
        (try? archiveCaseFixture().cases) ?? []
    }

    /// The canonical archive of `formatVersion` 1, kept forever (`fixtures/archive/v1/golden.medicabinet`).
    public static func goldenArchive(version: Int = 1) throws -> [UInt8] {
        [UInt8](try data("archive/v\(version)/golden.medicabinet"))
    }

    public static func goldenArchiveExists(version: Int) -> Bool {
        FileManager.default.fileExists(atPath: directory.appendingPathComponent("archive/v\(version)/golden.medicabinet").path)
    }
}
