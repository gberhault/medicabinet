package com.medicabinet.core.archive

private const val INDENT_WIDTH = 2
private const val CONTROL_LIMIT = 0x20

/**
 * Writes the archive's canonical JSON (design §2.3), byte for byte the same on both platforms: every
 * non-empty object and array spans several lines (two-space indent, one member or element per line),
 * `": "` after keys, LF line endings, `[]` for an empty array, and a final newline from [finish].
 * Strings escape only `"`, `\`, `\b \f \n \r \t` and the other control characters below U+0020 (as
 * lower-case `\u00xx`); everything else is raw UTF-8.
 */
internal class CanonicalJsonWriter {
    private val out = StringBuilder()
    private val layout = JsonLayout(out)

    fun beginObject() {
        layout.beforeValue()
        out.append('{')
        layout.open()
    }

    fun endObject() = layout.close('}')

    fun beginArray() {
        layout.beforeValue()
        out.append('[')
        layout.open()
    }

    fun endArray() = layout.close(']')

    fun key(name: String) {
        layout.startMember()
        appendQuoted(out, name)
        out.append(": ")
        layout.expectValue()
    }

    fun string(value: String) {
        layout.beforeValue()
        appendQuoted(out, value)
    }

    fun integer(value: Long) {
        layout.beforeValue()
        out.append(value)
    }

    fun bool(value: Boolean) {
        layout.beforeValue()
        out.append(value)
    }

    fun nul() {
        layout.beforeValue()
        out.append("null")
    }

    /** The finished document: the root value followed by a newline. */
    fun finish(): ByteArray {
        out.append('\n')
        return out.toString().toByteArray(Charsets.UTF_8)
    }
}

/** Where we are in the document, so values and members land on the right line with the right indent. */
private class JsonLayout(private val out: StringBuilder) {
    /** One entry per open object / array: how many members or elements it has so far. */
    private val counts = ArrayList<Int>()
    private var afterKey = false

    fun open() {
        counts.add(0)
    }

    /** Called before every value: after a key nothing is needed; as an array element or the root, layout applies. */
    fun beforeValue() {
        if (afterKey) {
            afterKey = false
        } else if (counts.isNotEmpty()) {
            startMember()
        }
    }

    fun expectValue() {
        afterKey = true
    }

    fun startMember() {
        val last = counts.lastIndex
        if (counts[last] > 0) out.append(',')
        counts[last] += 1
        out.append('\n')
        indent(counts.size)
    }

    fun close(bracket: Char) {
        val members = counts.removeAt(counts.lastIndex)
        if (members > 0) {
            out.append('\n')
            indent(counts.size)
        }
        out.append(bracket)
    }

    private fun indent(levels: Int) {
        repeat(levels * INDENT_WIDTH) { out.append(' ') }
    }
}

private fun appendQuoted(out: StringBuilder, text: String) {
    out.append('"')
    for (char in text) {
        when {
            char == '"' -> out.append("\\\"")
            char == '\\' -> out.append("\\\\")
            char == '\b' -> out.append("\\b")
            char == '\u000C' -> out.append("\\f")
            char == '\n' -> out.append("\\n")
            char == '\r' -> out.append("\\r")
            char == '\t' -> out.append("\\t")
            char.code < CONTROL_LIMIT -> out.append("\\u00").append("%02x".format(char.code))
            else -> out.append(char)
        }
    }
    out.append('"')
}
