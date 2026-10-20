package com.medicabinet.core.archive

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class ArchiveObjectReadingTest {
    private class Draft {
        var number = 0L
    }

    private fun reader(text: String) = JsonReader(text.toByteArray(), 100)

    private fun expectError(kind: ArchiveErrorKind, path: String?, block: () -> Unit) {
        val error = assertFailsWith<ArchiveException> { block() }
        assertEquals(kind, error.kind)
        assertEquals(path, error.path)
    }

    private val numberField = ObjectField<Draft>("n") { draft, json, field -> draft.number = json.readInteger(field) }

    @Test
    fun anObjectIsReadIntoItsDraft() {
        val draft = Draft()
        reader("{\"n\": 42}").readObject("box", draft, listOf(numberField))
        assertEquals(42, draft.number)
    }

    @Test
    fun aValueOfTheWrongTypeIsAStructureErrorAtThatField() {
        expectError(ArchiveErrorKind.INVALID_STRUCTURE, "box.n") {
            reader("{\"n\":\"text\"}").readObject("box", Draft(), listOf(numberField))
        }
    }

    @Test
    fun anUnknownDuplicateOrMissingKeyIsAStructureError() {
        expectError(ArchiveErrorKind.INVALID_STRUCTURE, "box.other") {
            reader("{\"other\":1}").readObject("box", Draft(), listOf(numberField))
        }
        expectError(ArchiveErrorKind.INVALID_STRUCTURE, "box.n") {
            reader("{\"n\":1,\"n\":2}").readObject("box", Draft(), listOf(numberField))
        }
        expectError(ArchiveErrorKind.INVALID_STRUCTURE, "box.n") {
            reader("{}").readObject("box", Draft(), listOf(numberField))
        }
    }

    @Test
    fun anObjectThatIsNotAnObjectIsAStructureErrorAtItsPath() {
        expectError(ArchiveErrorKind.INVALID_STRUCTURE, "box") {
            reader("[]").readObject("box", Draft(), listOf(numberField))
        }
    }

    @Test
    fun aListIsWalkedAndStopsAtTheLimit() {
        val parsed = reader("[10, 20]")
        val cursor = ListCursor(parsed, "list", 2)
        val indexes = ArrayList<Int>()
        var index = cursor.next(parsed)
        while (index != null) {
            indexes.add(index)
            parsed.readInteger(FieldPath("list", "item"))
            index = cursor.next(parsed)
        }
        assertEquals(listOf(0, 1), indexes)

        val over = reader("[10, 20]")
        val limited = ListCursor(over, "list", 1)
        assertEquals(0, limited.next(over))
        over.readInteger(FieldPath("list", "item"))
        expectError(ArchiveErrorKind.TOO_MANY_ITEMS, "list") { limited.next(over) }
    }

    @Test
    fun aListThatIsNotAnArrayIsAStructureError() {
        expectError(ArchiveErrorKind.INVALID_STRUCTURE, "list") { ListCursor(reader("{}"), "list", 1) }
    }
}
