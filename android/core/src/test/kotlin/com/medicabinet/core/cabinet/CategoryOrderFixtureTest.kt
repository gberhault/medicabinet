package com.medicabinet.core.cabinet

import com.medicabinet.core.CategoryID
import com.medicabinet.core.reference.Taxonomy
import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.categoryOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs every case of `fixtures/category-order.json`, which the Swift core also runs. */
class CategoryOrderFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.categoryOrder()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 8, "expected at least 8 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.categoryOrder().cases.map { case ->
        dynamicTest(case.name) {
            val labels = case.labels.mapKeys { CategoryID(it.key) }
            assertEquals(case.expect, CategoryOrder.alphabetical(labels).map { it.rawValue })
        }
    }
}

class CategoryOrderTest {
    @Test
    fun everyShippedCategoryIsOrderedExactlyOnce() {
        val order = CategoryOrder.alphabetical(Taxonomy.ordered.associateWith { it.rawValue })
        assertEquals(Taxonomy.ordered.toSet(), order.toSet())
        assertEquals(Taxonomy.ordered.size, order.size)
    }
}
