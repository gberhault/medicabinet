import Foundation
import Testing
@testable import MediCabinetCore

@Suite struct ArchiveObjectReadingTests {
    private struct Unexpected: Error {}

    private struct Draft {
        var number: Int64 = 0
    }

    private func reader(_ text: String) throws -> JSONReader {
        try JSONReader(Array(text.utf8), maxStringBytes: 100)
    }

    @Test func aFieldReaderThatThrowsAnotherErrorIsReportedAsAStructureErrorAtThatField() throws {
        var parsed = try reader("{\"n\":1}")
        var draft = Draft()
        let fields = [ObjectField<Draft>(key: "n") { _, _, _ in throw Unexpected() }]
        #expect(throws: ArchiveError(.invalidStructure, path: "box.n")) {
            try ArchiveObjectReading.readObject(&parsed, path: "box", into: &draft, fields: fields)
        }
    }

    @Test func anArchiveErrorFromAFieldReaderKeepsItsKindAndPath() throws {
        var parsed = try reader("{\"n\":\"text\"}")
        var draft = Draft()
        let fields = [ObjectField<Draft>(key: "n") { draft, reader, field in draft.number = try reader.readInteger(field) }]
        #expect(throws: ArchiveError(.invalidStructure, path: "box.n")) {
            try ArchiveObjectReading.readObject(&parsed, path: "box", into: &draft, fields: fields)
        }
    }

    @Test func aListIsWalkedAndStopsAtTheLimit() throws {
        var parsed = try reader("[10, 20]")
        var cursor = try ArchiveObjectReading.ListCursor(&parsed, path: "list", limit: 2)
        var indexes: [Int] = []
        while let index = try cursor.next(&parsed) {
            indexes.append(index)
            _ = try parsed.readInteger(FieldPath("list", "item"))
        }
        #expect(indexes == [0, 1])

        var over = try reader("[10, 20]")
        var limited = try ArchiveObjectReading.ListCursor(&over, path: "list", limit: 1)
        #expect(try limited.next(&over) == 0)
        _ = try over.readInteger(FieldPath("list", "item"))
        #expect(throws: ArchiveError(.tooManyItems, path: "list")) { try limited.next(&over) }
    }

    @Test func aListThatIsNotAnArrayIsAStructureError() throws {
        var parsed = try reader("{}")
        #expect(throws: ArchiveError(.invalidStructure, path: "list")) {
            try ArchiveObjectReading.ListCursor(&parsed, path: "list", limit: 1)
        }
    }
}
