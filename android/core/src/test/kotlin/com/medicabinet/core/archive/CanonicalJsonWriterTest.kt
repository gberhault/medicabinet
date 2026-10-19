package com.medicabinet.core.archive

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class CanonicalJsonWriterTest {
    private fun text(build: CanonicalJsonWriter.() -> Unit): String =
        CanonicalJsonWriter().apply(build).finish().toString(Charsets.UTF_8)

    @Test
    fun nestedContainersSpanSeveralLinesWithTwoSpaceIndent() {
        val output = text {
            beginObject()
            key("a")
            integer(1)
            key("list")
            beginArray()
            string("x")
            beginObject()
            key("n")
            nul()
            key("b")
            bool(true)
            endObject()
            bool(false)
            endArray()
            endObject()
        }
        val expected = """
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
        """.trimIndent() + "\n"
        assertEquals(expected, output)
    }

    @Test
    fun emptyContainersStayOnOneLine() {
        val output = text {
            beginObject()
            key("none")
            beginArray()
            endArray()
            key("nothing")
            beginObject()
            endObject()
            endObject()
        }
        assertEquals("{\n  \"none\": [],\n  \"nothing\": {}\n}\n", output)
    }

    @Test
    fun aRootScalarIsFollowedByANewline() {
        assertEquals("-42\n", text { integer(-42) })
    }

    @Test
    fun stringsEscapeOnlyWhatJsonRequires() {
        val output = text { string("q\" b\\ \b\u000C\n\r\t \u0000\u001F\u007F / é € 🧳") }
        assertEquals("\"q\\\" b\\\\ \\b\\f\\n\\r\\t \\u0000\\u001f\u007F / é € 🧳\"\n", output)
    }

    @Test
    fun keysAreEscapedLikeStrings() {
        val output = text {
            beginObject()
            key("a\"b")
            integer(Long.MIN_VALUE)
            endObject()
        }
        assertEquals("{\n  \"a\\\"b\": -9223372036854775808\n}\n", output)
    }

    @Test
    fun whatTheWriterWritesTheReaderReadsBack() {
        val output = text {
            beginObject()
            key("s")
            string("é\u0001\"🧳")
            key("i")
            integer(7)
            endObject()
        }
        val reader = JsonReader(output.toByteArray(), 100)
        reader.beginObject()
        assertEquals("s", reader.nextKey(true))
        assertEquals("é\u0001\"🧳", reader.readString(FieldPath("s")))
        assertEquals("i", reader.nextKey(false))
        assertEquals(7, reader.readInteger(FieldPath("i")))
        assertEquals(null, reader.nextKey(false))
        reader.finish()
    }
}
