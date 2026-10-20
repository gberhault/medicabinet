package com.medicabinet.core.reference

import com.medicabinet.core.CategoryID
import com.medicabinet.core.testing.SharedFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * [Taxonomy] against the repository-wide `fixtures/categories.json`, which the Swift core and the reference-data build
 * also use.
 */
class TaxonomyFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasTwelveCategories() {
        assertEquals(12, SharedFixtures.categories().size)
    }

    @Test
    fun orderedMatchesTheFixtureInOrder() {
        assertEquals(SharedFixtures.categories().map { it.name }, Taxonomy.ordered.map { it.rawValue })
    }

    @Test
    fun userSelectableMatchesTheFixture() {
        val fixture = SharedFixtures.categories().filter { it.userSelectable }.map { it.name }
        assertEquals(fixture, Taxonomy.userSelectable.map { it.rawValue })
        assertFalse(CategoryID.UNCATEGORIZED in Taxonomy.userSelectable)
    }

    @Test
    fun firstAidIsSelectableButNeverAssignedByTheBuild() {
        val firstAid = SharedFixtures.categories().first { it.name == "first-aid" }
        assertTrue(firstAid.userSelectable)
        assertFalse(firstAid.assignedByBuild)
    }

    @Test
    fun uncategorizedMatchesTheModelConstantAndComesLast() {
        assertEquals("uncategorized", CategoryID.UNCATEGORIZED.rawValue)
        assertEquals(CategoryID.UNCATEGORIZED, Taxonomy.ordered.last())
    }

    @Test
    fun everyKnownCategoryIsDisplayedAsItself() {
        Taxonomy.ordered.forEach { assertEquals(it, Taxonomy.displayed(it)) }
    }

    @Test
    fun anUnknownCategoryIsDisplayedAsOtherWithoutChangingTheStoredValue() {
        val fromANewerApp = CategoryID("veterinary")
        assertEquals(CategoryID.OTHER, Taxonomy.displayed(fromANewerApp))
        assertEquals("veterinary", fromANewerApp.rawValue)
        assertEquals(CategoryID.OTHER, Taxonomy.displayed(CategoryID("")))
    }
}
