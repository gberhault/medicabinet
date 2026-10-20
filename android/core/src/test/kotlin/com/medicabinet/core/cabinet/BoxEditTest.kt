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
        assertTrue(BoxEditError.entries.size == 3)
    }

    // category (B4)

    private fun autoBox() = Box(
        product = ProductRef("03400930000014", "Doliprane"),
        category = CategoryID.PAIN_FEVER,
        locationId = kitchen,
        createdAt = Instant.ofEpochSecond(100)
    )

    @Test
    fun aChosenCategoryMarksTheBoxUserChosen() {
        val edit = BoxEdit.from(autoBox()).chooseCategory(CategoryID.FIRST_AID)
        val edited = edit.applyTo(autoBox())
        assertEquals(CategoryID.FIRST_AID, edited.category)
        assertTrue(edited.categoryOverridden)
    }

    @Test
    fun onlyACategoryTheUserMayPickCanBeChosen() {
        val edit = BoxEdit.from(autoBox())
        listOf(CategoryID.UNCATEGORIZED, CategoryID("from-the-future")).forEach {
            val failure = assertFailsWith<BoxEditException> { edit.chooseCategory(it) }
            assertEquals(BoxEditError.CATEGORY_NOT_SELECTABLE, failure.error)
        }
        // A refused choice changes nothing.
        assertNull(edit.categoryChange)
    }

    @Test
    fun automaticCategoryGivesTheBoxBackToTheClassifier() {
        val userChosen = autoBox().copy(category = CategoryID.FIRST_AID, categoryOverridden = true)
        val edited = BoxEdit.from(userChosen).resetCategory(CategoryID.PAIN_FEVER).applyTo(userChosen)
        assertEquals(CategoryID.PAIN_FEVER, edited.category)
        assertEquals(false, edited.categoryOverridden)
    }

    @Test
    fun aLaterChoiceReplacesAnEarlierOne() {
        val edit = BoxEdit.from(autoBox())
            .chooseCategory(CategoryID.FIRST_AID)
            .resetCategory(CategoryID.UNCATEGORIZED)
            .chooseCategory(CategoryID.EYES)
        assertEquals(CategoryID.EYES, edit.applyTo(autoBox()).category)
    }

    @Test
    fun withoutACategoryChangeTheCategoryIsUntouched() {
        val chosen = autoBox().copy(category = CategoryID.EYES, categoryOverridden = true)
        val edited = BoxEdit("Doliprane", null, kitchen).applyTo(chosen)
        assertEquals(CategoryID.EYES, edited.category)
        assertTrue(edited.categoryOverridden)
    }

    @Test
    fun theCategoryErrorKeepsItsPlatformNeutralName() {
        assertEquals("categoryNotSelectable", BoxEditError.CATEGORY_NOT_SELECTABLE.kind)
    }
}
