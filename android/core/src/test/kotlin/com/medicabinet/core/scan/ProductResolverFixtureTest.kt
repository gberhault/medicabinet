package com.medicabinet.core.scan

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ProductRef
import com.medicabinet.core.testing.SharedFixtures
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** Runs every case of the repository-wide `fixtures/product-resolution.json`, which the Swift core also runs. */
class ProductResolverFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        assertTrue(SharedFixtures.productResolutionCases().size >= 8)
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.productResolutionCases().map { case ->
        dynamicTest(case.name) {
            val reference = case.reference?.let { ReferenceProduct(it.name, it.category?.let(::CategoryID)) }
            val previous = case.previousBox?.let {
                Box(
                    product = ProductRef("03400930000014", it.name),
                    category = CategoryID(it.category),
                    categoryOverridden = it.categoryOverridden,
                    locationId = UUID.randomUUID(),
                    createdAt = Instant.EPOCH
                )
            }
            val resolved = ProductResolver.resolve(reference, previous)
            assertEquals(case.expect.name, resolved.name)
            assertEquals(case.expect.category, resolved.category.rawValue)
            assertEquals(case.expect.categoryOverridden, resolved.categoryOverridden)
        }
    }
}
