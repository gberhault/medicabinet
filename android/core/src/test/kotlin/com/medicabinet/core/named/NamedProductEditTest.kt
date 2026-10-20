package com.medicabinet.core.named

import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProduct
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamedProductEditTest {
    private val product = NamedProduct(
        gtin = "03401900000003",
        name = "Test",
        category = CategoryID.SKIN_WOUND,
        categoryOverridden = true,
        createdAt = Instant.EPOCH
    )
    private val automatic = product.copy(category = CategoryID.UNCATEGORIZED, categoryOverridden = false)

    private fun errorOf(edit: NamedProductEdit): NamedProductEditError =
        assertFailsWith<NamedProductEditException> { edit.validated() }.error

    @Test
    fun anUntouchedFormChangesNothing() {
        assertTrue(NamedProductEdit.from(product).validated().isEmpty)
    }

    @Test
    fun theNameIsTrimmedAndOnlyItIsReturnedWhenOnlyItChanged() {
        val changes = NamedProductEdit.from(product).copy(name = "  Crème solaire \n").validated()
        assertEquals("Crème solaire", changes.name)
        assertNull(changes.category)
    }

    @Test
    fun aNameEqualToTheCurrentOneOnceTrimmedIsNotAChange() {
        assertNull(NamedProductEdit.from(product).copy(name = " Test ").validated().name)
    }

    @Test
    fun anEmptyNameIsRefused() {
        assertEquals(NamedProductEditError.EMPTY_NAME, errorOf(NamedProductEdit.from(product).copy(name = "")))
        assertEquals(NamedProductEditError.EMPTY_NAME, errorOf(NamedProductEdit.from(product).copy(name = "   ")))
    }

    @Test
    fun twoHundredCodePointsPassAndTwoHundredAndOneAreRefused() {
        val emoji = "💊"
        assertEquals(emoji.repeat(200), NamedProductEdit.from(product).copy(name = emoji.repeat(200)).validated().name)
        assertEquals(
            NamedProductEditError.NAME_TOO_LONG,
            errorOf(NamedProductEdit.from(product).copy(name = emoji.repeat(201)))
        )
    }

    @Test
    fun aChosenCategoryIsMarkedAsChosenByTheUser() {
        val changes = NamedProductEdit.from(automatic).chooseCategory(CategoryID.EYES).validated()
        assertNull(changes.name)
        assertEquals(NamedProductChanges.Category(CategoryID.EYES, overridden = true), changes.category)
    }

    @Test
    fun aCategoryTheUserMayNotPickIsRefused() {
        val edit = NamedProductEdit.from(product)
        for (refused in listOf(CategoryID.UNCATEGORIZED, CategoryID("made-up"))) {
            val failure = assertFailsWith<NamedProductEditException> { edit.chooseCategory(refused) }
            assertEquals(NamedProductEditError.CATEGORY_NOT_SELECTABLE, failure.error)
        }
    }

    @Test
    fun theAutomaticCategoryResetsToUncategorizedWithTheFlagOff() {
        val changes = NamedProductEdit.from(product).resetCategory().validated()
        assertEquals(NamedProductChanges.Category(CategoryID.UNCATEGORIZED, overridden = false), changes.category)
    }

    @Test
    fun resetOnAProductWithAnAutomaticCategoryChangesNothing() {
        assertTrue(NamedProductEdit.from(automatic).resetCategory().validated().isEmpty)
    }

    @Test
    fun choosingTheSameCategoryAgainChangesNothing() {
        assertTrue(NamedProductEdit.from(product).chooseCategory(CategoryID.SKIN_WOUND).validated().isEmpty)
    }

    @Test
    fun aNameAndACategoryChangeTogetherAreBothReturned() {
        val changes = NamedProductEdit.from(
            automatic
        ).copy(name = "Collyre").chooseCategory(CategoryID.EYES).validated()
        assertEquals("Collyre", changes.name)
        assertEquals(CategoryID.EYES, changes.category?.id)
    }

    @Test
    fun anInvalidNameIsRefusedEvenWhenTheCategoryChanged() {
        val edit = NamedProductEdit.from(product).copy(name = "").chooseCategory(CategoryID.EYES)
        assertEquals(NamedProductEditError.EMPTY_NAME, errorOf(edit))
    }

    @Test
    fun theErrorsUseThePlatformNeutralNames() {
        assertEquals(
            listOf("emptyName", "nameTooLong", "categoryNotSelectable"),
            NamedProductEditError.entries.map { it.kind }
        )
    }
}
