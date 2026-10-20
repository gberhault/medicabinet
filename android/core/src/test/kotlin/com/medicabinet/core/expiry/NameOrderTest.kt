package com.medicabinet.core.expiry

import com.medicabinet.core.Box
import com.medicabinet.core.ProductRef
import com.medicabinet.core.cabinet.TextNormalizer
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class NameOrderTest {
    private fun box(name: String, id: String = "00000000-0000-0000-0000-000000000001") = Box(
        id = UUID.fromString(id),
        product = ProductRef(name = name),
        locationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH
    )

    @Test
    fun foldingLowersCaseAndRemovesAccents() {
        assertEquals("efferalgan", NameOrder.folded("Éfferalgan"))
        assertEquals("efferalgan", NameOrder.folded("ÉFFERALGAN"))
        assertEquals(TextNormalizer.normalize("Crème à l’œuf"), NameOrder.folded("Crème à l’œuf"))
        assertEquals("", NameOrder.folded(""))
    }

    @Test
    fun foldingDecomposesPrecomposedAndCombiningFormsAlike() {
        assertEquals(NameOrder.folded("é"), NameOrder.folded("é"))
    }

    @Test
    fun accentsAndCaseDoNotSeparateNames() {
        assertTrue(NameOrder.precedes(box("Éfferalgan"), box("Zyrtec")))
        assertFalse(NameOrder.precedes(box("Zyrtec"), box("éfferalgan")))
        assertTrue(NameOrder.precedes(box("advil"), box("Éfferalgan")))
    }

    @Test
    fun equalFoldedNamesBreakByLowerCaseId() {
        val low = box("Doliprane", "00000000-0000-0000-0000-00000000000a")
        val high = box("doliprane", "00000000-0000-0000-0000-00000000000b")
        assertTrue(NameOrder.precedes(low, high))
        assertFalse(NameOrder.precedes(high, low))
    }

    @Test
    fun aBoxDoesNotPrecedeItself() {
        val value = box("Doliprane")
        assertFalse(NameOrder.precedes(value, value))
    }

    @Test
    fun ordersByCodePointNotLocale() {
        // Digits sort before letters, and a space before both.
        assertTrue(NameOrder.precedes(box("2 Doliprane"), box("Advil")))
        assertTrue(NameOrder.precedes(box("A B"), box("AB")))
    }

    @Test
    fun aPrefixSortsBeforeALongerName() {
        assertTrue(NameOrder.precedes(box("Dol"), box("Doliprane")))
        assertFalse(NameOrder.precedes(box("Doliprane"), box("Dol")))
    }

    @Test
    fun supplementaryCharactersSortByCodePointNotUtf16Unit() {
        // U+1F48A (a surrogate pair in UTF-16) is above U+FF21 as a code point but below it as UTF-16 units.
        assertTrue(NameOrder.precedes(box("Ａ"), box("💊")))
    }
}
