/// Writes the archive's canonical JSON (design §2.3), byte for byte the same on both platforms:
/// every non-empty object and array spans several lines (two-space indent, one member or
/// element per line), `": "` after keys, LF line endings, `[]` for an empty array, and a final
/// newline from `finish()`. Strings escape only `"`, `\`, `\b \f \n \r \t` and the other
/// control characters below U+0020 (as lower-case `\u00xx`); everything else is raw UTF-8.
struct CanonicalJSONWriter {
    private(set) var bytes: [UInt8] = []
    /// One entry per open object / array: how many members or elements it has so far.
    private var counts: [Int] = []
    private var afterKey = false

    mutating func beginObject() {
        prepareValue()
        bytes.append(UInt8(ascii: "{"))
        counts.append(0)
    }

    mutating func endObject() {
        close(UInt8(ascii: "}"))
    }

    mutating func beginArray() {
        prepareValue()
        bytes.append(UInt8(ascii: "["))
        counts.append(0)
    }

    mutating func endArray() {
        close(UInt8(ascii: "]"))
    }

    mutating func key(_ name: String) {
        startMember()
        appendQuoted(name)
        bytes.append(contentsOf: [UInt8(ascii: ":"), UInt8(ascii: " ")])
        afterKey = true
    }

    mutating func string(_ value: String) {
        prepareValue()
        appendQuoted(value)
    }

    mutating func integer(_ value: Int64) {
        prepareValue()
        bytes.append(contentsOf: Array(String(value).utf8))
    }

    mutating func bool(_ value: Bool) {
        prepareValue()
        bytes.append(contentsOf: Array((value ? "true" : "false").utf8))
    }

    mutating func null() {
        prepareValue()
        bytes.append(contentsOf: Array("null".utf8))
    }

    /// The finished document: the root value followed by a newline.
    mutating func finish() -> [UInt8] {
        bytes.append(0x0A)
        return bytes
    }

    // MARK: Layout

    /// Called before every value: after a key nothing is needed; as an array element or the root, layout applies.
    private mutating func prepareValue() {
        if afterKey {
            afterKey = false
        } else if !counts.isEmpty {
            startMember()
        }
    }

    private mutating func startMember() {
        if counts[counts.count - 1] > 0 { bytes.append(UInt8(ascii: ",")) }
        counts[counts.count - 1] += 1
        bytes.append(0x0A)
        indent(counts.count)
    }

    private mutating func close(_ bracket: UInt8) {
        let members = counts.removeLast()
        if members > 0 {
            bytes.append(0x0A)
            indent(counts.count)
        }
        bytes.append(bracket)
    }

    private mutating func indent(_ levels: Int) {
        bytes.append(contentsOf: repeatElement(UInt8(ascii: " "), count: levels * 2))
    }

    // MARK: Strings

    private mutating func appendQuoted(_ text: String) {
        bytes.append(UInt8(ascii: "\""))
        for byte in text.utf8 {
            switch byte {
            case UInt8(ascii: "\""): bytes.append(contentsOf: [UInt8(ascii: "\\"), UInt8(ascii: "\"")])
            case UInt8(ascii: "\\"): bytes.append(contentsOf: [UInt8(ascii: "\\"), UInt8(ascii: "\\")])
            case 0x08: bytes.append(contentsOf: [UInt8(ascii: "\\"), UInt8(ascii: "b")])
            case 0x0C: bytes.append(contentsOf: [UInt8(ascii: "\\"), UInt8(ascii: "f")])
            case 0x0A: bytes.append(contentsOf: [UInt8(ascii: "\\"), UInt8(ascii: "n")])
            case 0x0D: bytes.append(contentsOf: [UInt8(ascii: "\\"), UInt8(ascii: "r")])
            case 0x09: bytes.append(contentsOf: [UInt8(ascii: "\\"), UInt8(ascii: "t")])
            case 0x00..<0x20:
                let digits = Array("0123456789abcdef".utf8)
                bytes.append(contentsOf: Array("\\u00".utf8))
                bytes.append(digits[Int(byte >> 4)])
                bytes.append(digits[Int(byte & 0x0F)])
            default: bytes.append(byte)
            }
        }
        bytes.append(UInt8(ascii: "\""))
    }
}
