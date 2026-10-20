/// Archive lengths are counted in Unicode code points, like SQLite's `length()`: not grapheme
/// clusters (Swift `Character`) and not UTF-16 units (Kotlin `String.length`).
/// `fixtures/archive-code-points.json` proves both platforms agree.
func codePointCount(_ string: String) -> Int {
    string.unicodeScalars.count
}
