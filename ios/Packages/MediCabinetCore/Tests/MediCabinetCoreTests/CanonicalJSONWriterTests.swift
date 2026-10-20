import Foundation
import Testing
@testable import MediCabinetCore

@Suite struct CanonicalJSONWriterTests {
    private func text(_ build: (inout CanonicalJSONWriter) -> Void) -> String {
        var writer = CanonicalJSONWriter()
        build(&writer)
        return String(bytes: writer.finish(), encoding: .utf8) ?? "<invalid UTF-8>"
    }

    @Test func nestedContainersSpanSeveralLinesWithTwoSpaceIndent() {
        let output = text { writer in
            writer.beginObject()
            writer.key("a")
            writer.integer(1)
            writer.key("list")
            writer.beginArray()
            writer.string("x")
            writer.beginObject()
            writer.key("n")
            writer.null()
            writer.key("b")
            writer.bool(true)
            writer.endObject()
            writer.bool(false)
            writer.endArray()
            writer.endObject()
        }
        let expected = """
        {
          "a": 1,
          "list": [
            "x",
            {
              "n": null,
              "b": true
            },
            false
          ]
        }

        """
        #expect(output == expected)
    }

    @Test func emptyContainersStayOnOneLine() {
        let output = text { writer in
            writer.beginObject()
            writer.key("none")
            writer.beginArray()
            writer.endArray()
            writer.key("nothing")
            writer.beginObject()
            writer.endObject()
            writer.endObject()
        }
        #expect(output == "{\n  \"none\": [],\n  \"nothing\": {}\n}\n")
    }

    @Test func aRootScalarIsFollowedByANewline() {
        #expect(text { $0.integer(-42) } == "-42\n")
    }

    @Test func stringsEscapeOnlyWhatJSONRequires() {
        let output = text { $0.string("q\" b\\ \u{08}\u{0C}\n\r\t \u{00}\u{1F}\u{7F} / é € 🧳") }
        #expect(output == "\"q\\\" b\\\\ \\b\\f\\n\\r\\t \\u0000\\u001f\u{7F} / é € 🧳\"\n")
    }

    @Test func keysAreEscapedLikeStrings() {
        let output = text { writer in
            writer.beginObject()
            writer.key("a\"b")
            writer.integer(Int64.min)
            writer.endObject()
        }
        #expect(output == "{\n  \"a\\\"b\": -9223372036854775808\n}\n")
    }

    @Test func whatTheWriterWritesTheReaderReadsBack() throws {
        let output = text { writer in
            writer.beginObject()
            writer.key("s")
            writer.string("é\u{01}\"🧳")
            writer.key("i")
            writer.integer(7)
            writer.endObject()
        }
        var reader = try JSONReader(Array(output.utf8), maxStringBytes: 100)
        try reader.beginObject()
        #expect(try reader.nextKey(isFirst: true) == "s")
        #expect(try reader.readString(FieldPath("", "s")) == "é\u{01}\"🧳")
        #expect(try reader.nextKey(isFirst: false) == "i")
        #expect(try reader.readInteger(FieldPath("", "i")) == 7)
        #expect(try reader.nextKey(isFirst: false) == nil)
        try reader.finish()
    }
}
