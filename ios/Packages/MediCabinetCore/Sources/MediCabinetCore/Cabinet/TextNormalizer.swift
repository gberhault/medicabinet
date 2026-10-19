import Foundation

/// Text comparison for search, location-name uniqueness and name ordering. The steps are spelled out
/// (rather than `folding(options:locale:)`) so the Kotlin twin produces the same text, pinned by
/// `fixtures/text-normalization.json`.
public enum TextNormalizer {
    /// The Unicode `White_Space` property, written out because `Character.isWhitespace` on the JVM
    /// excludes the no-break spaces.
    public static func isWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x09...0x0D, 0x20, 0x85, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000:
            true
        default:
            false
        }
    }

    /// Removes leading and trailing `White_Space`; inner text is untouched.
    public static func trimmed(_ text: String) -> String {
        let scalars = Array(text.unicodeScalars)
        guard let first = scalars.firstIndex(where: { !isWhitespace($0) }),
              let last = scalars.lastIndex(where: { !isWhitespace($0) })
        else { return "" }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars[first...last])
        return String(view)
    }

    /// NFD, lower-case per scalar (locale-independent), drop non-spacing marks, fold `œ æ ß ’ ‘`, collapse
    /// whitespace runs to one space, trim.
    public static func normalize(_ text: String) -> String {
        var result = String.UnicodeScalarView()
        var pendingSpace = false
        for decomposed in text.decomposedStringWithCanonicalMapping.unicodeScalars {
            for scalar in decomposed.properties.lowercaseMapping.unicodeScalars {
                if scalar.properties.generalCategory == .nonspacingMark { continue }
                if isWhitespace(scalar) {
                    pendingSpace = true
                    continue
                }
                if pendingSpace {
                    if !result.isEmpty { result.append(" ") }
                    pendingSpace = false
                }
                if let folded = fold(scalar) {
                    result.append(contentsOf: folded.unicodeScalars)
                } else {
                    result.append(scalar)
                }
            }
        }
        return String(result)
    }

    /// Every whitespace-separated word of `query` is a substring of `text` (both normalized); an empty query
    /// matches everything.
    public static func matches(_ text: String, query: String) -> Bool {
        matches(normalizedText: normalize(text), query: query)
    }

    /// Same as `matches(_:query:)` for a text that is already normalized (the list normalizes each row once).
    public static func matches(normalizedText: String, query: String) -> Bool {
        let words = normalize(query).split(separator: " ")
        if words.isEmpty { return true }
        let haystack = Array(normalizedText.unicodeScalars)
        return words.allSatisfy { contains(haystack, Array($0.unicodeScalars)) }
    }

    /// Orders by Unicode scalar value (not by locale collation, not by canonical equivalence), as the JVM
    /// twin does by code point.
    public static func compare(_ lhs: String, _ rhs: String) -> Int {
        let left = lhs.unicodeScalars.map(\.value)
        let right = rhs.unicodeScalars.map(\.value)
        for (leftValue, rightValue) in zip(left, right) where leftValue != rightValue {
            return leftValue < rightValue ? -1 : 1
        }
        return left.count == right.count ? 0 : (left.count < right.count ? -1 : 1)
    }

    private static func fold(_ scalar: Unicode.Scalar) -> String? {
        switch scalar.value {
        case 0x153: "oe"  // œ
        case 0xE6: "ae"  // æ
        case 0xDF: "ss"  // ß
        case 0x2019, 0x2018: "'"  // ’ ‘
        default: nil
        }
    }

    private static func contains(_ haystack: [Unicode.Scalar], _ needle: [Unicode.Scalar]) -> Bool {
        if needle.isEmpty { return true }
        if needle.count > haystack.count { return false }
        for start in 0...(haystack.count - needle.count)
        where haystack[start..<(start + needle.count)].elementsEqual(needle) {
            return true
        }
        return false
    }
}
