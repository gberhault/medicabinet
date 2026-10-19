package com.medicabinet.core.cabinet

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ProductRef
import com.medicabinet.core.scan.ScanIntake
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BoxEditTest {
    private val kitchen = UUID.randomUUID()
    private val bathroom = UUID.randomUUID()

    private fun box(expiry: ExpiryDate? = null) = Box(
        product = ProductRef("03400930000014", "Doliprane"),
        category = CategoryID("pain-fever"),
        categoryOverridden = true,
        locationId = kitchen,
        expiry = expiry,
        lot = "LOT1",
        serial = "SER1",
        isOpened = true,
        createdAt = Instant.ofEpochSecond(100),
        updatedAt = Instant.ofEpochSecond(200)
    )

    @Test
    fun theFormStartsFromTheBoxAndAnUnknownExpiryStaysUnknown() {
        assertNull(BoxEdit.from(box()).expiry)
        val known = box(ExpiryDate.of(2027, 3, null))
        val edit = BoxEdit.from(known)
        assertEquals("Doliprane", edit.name)
        assertEquals(known.expiry, edit.expiry)
        assertEquals(kitchen, edit.locationId)
    }

    @Test
    fun applyingChangesTheNameExpiryAndLocationOnly() {
        val original = box()
        val expiry = ExpiryDate.of(2028, 2, 29)
        val edited = BoxEdit("  Doliprane 500 mg ", expiry, bathroom).applyTo(original)
        assertEquals("Doliprane 500 mg", edited.product.name)
        assertEquals(expiry, edited.expiry)
        assertEquals(bathroom, edited.locationId)
        // Everything else is untouched, updatedAt included (the repository stamps it).
        val expected = original.copy(
            product = original.product.copy(name = "Doliprane 500 mg"),
            expiry = expiry,
            locationId = bathroom
        )
        assertEquals(expected, edited)
    }

    @Test
    fun anExpiryCanBeMadeUnknownOnlyByAnExplicitNull() {
        val original = box(ExpiryDate.of(2027, 3, null))
        assertNull(BoxEdit("Doliprane", null, kitchen).applyTo(original).expiry)
    }

    @Test
    fun anEmptyOrBlankNameIsRejected() {
        for (name in listOf("", "   ", " \t")) {
            val failure = assertFailsWith<BoxEditException> { BoxEdit(name, null, kitchen).applyTo(box()) }
            assertEquals(BoxEditError.EMPTY_NAME, failure.error)
        }
    }

    @Test
    fun theNameLimitIsCountedInCodePointsAfterTrimming() {
        val longest = "a".repeat(ScanIntake.MAX_NAME_LENGTH)
        assertEquals(longest, BoxEdit(" $longest ", null, kitchen).applyTo(box()).product.name)
        val failure = assertFailsWith<BoxEditException> { BoxEdit(longest + "a", null, kitchen).applyTo(box()) }
        assertEquals(BoxEditError.NAME_TOO_LONG, failure.error)
        // 200 emoji are 200 code points (400 UTF-16 units) and fit.
        val emoji = "💊".repeat(ScanIntake.MAX_NAME_LENGTH)
        assertEquals(emoji, BoxEdit(emoji, null, kitchen).applyTo(box()).product.name)
    }

    @Test
    fun errorKindsUseThePlatformNeutralNames() {
        assertEquals("emptyName", BoxEditError.EMPTY_NAME.kind)
        assertEquals("nameTooLong", BoxEditError.NAME_TOO_LONG.kind)
        assertTrue(BoxEditError.entries.size == 2)
    }
}
