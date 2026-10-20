/// The short form of a medicine name shown on a Cabinet pill: BDPM names read "NAME DOSE, pharmaceutical form",
/// so the cut is before the first ", ". A decimal comma ("0,05 %") has no space after it and is never cut. Display
/// only — a stored name is never rewritten. Pinned by `fixtures/short-names.json`; the Kotlin twin is `ShortName`.
public enum ShortName {
    public static func of(_ name: String) -> String {
        let trimmed = TextNormalizer.trimmed(name)
        let scalars = Array(trimmed.unicodeScalars)
        // U+002C then U+0020 only: a no-break space after the comma is part of the name.
        guard let cut = (0..<max(scalars.count - 1, 0)).first(where: { scalars[$0] == "," && scalars[$0 + 1] == " " })
        else { return trimmed }
        var head = String.UnicodeScalarView()
        head.append(contentsOf: scalars[..<cut])
        let short = TextNormalizer.trimmed(String(head))
        return short.isEmpty ? trimmed : short
    }
}
