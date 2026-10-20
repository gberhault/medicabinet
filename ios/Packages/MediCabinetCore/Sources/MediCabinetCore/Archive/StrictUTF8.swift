/// Strict UTF-8 validation (RFC 3629): no overlong forms, no surrogate code points, nothing
/// above U+10FFFF. Written out so the Swift and Kotlin readers refuse exactly the same bytes.
enum StrictUTF8 {
    private struct Rule {
        let lead: ClosedRange<UInt8>
        let length: Int
        /// The second byte's range narrows for E0, ED, F0 and F4 to exclude overlong forms, surrogates and values above U+10FFFF.
        let second: ClosedRange<UInt8>
    }

    private static let rules = [
        Rule(lead: 0xC2...0xDF, length: 2, second: 0x80...0xBF),
        Rule(lead: 0xE0...0xE0, length: 3, second: 0xA0...0xBF),
        Rule(lead: 0xE1...0xEC, length: 3, second: 0x80...0xBF),
        Rule(lead: 0xED...0xED, length: 3, second: 0x80...0x9F),
        Rule(lead: 0xEE...0xEF, length: 3, second: 0x80...0xBF),
        Rule(lead: 0xF0...0xF0, length: 4, second: 0x90...0xBF),
        Rule(lead: 0xF1...0xF3, length: 4, second: 0x80...0xBF),
        Rule(lead: 0xF4...0xF4, length: 4, second: 0x80...0x8F)
    ]

    static func isValid(_ bytes: [UInt8], from start: Int) -> Bool {
        var position = start
        while position < bytes.count {
            guard let length = sequenceLength(bytes, at: position) else { return false }
            position += length
        }
        return true
    }

    /// The length of the well-formed sequence starting at `position`, or `nil`.
    private static func sequenceLength(_ bytes: [UInt8], at position: Int) -> Int? {
        let lead = bytes[position]
        if lead < 0x80 { return 1 }
        guard let rule = rules.first(where: { $0.lead.contains(lead) }),
              position + rule.length <= bytes.count,
              rule.second.contains(bytes[position + 1])
        else { return nil }
        for offset in 2..<rule.length where bytes[position + offset] & 0xC0 != 0x80 { return nil }
        return rule.length
    }
}
