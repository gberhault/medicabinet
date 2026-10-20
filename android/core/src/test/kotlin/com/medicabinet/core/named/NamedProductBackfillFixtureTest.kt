package com.medicabinet.core.named

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.ProductRef
import com.medicabinet.core.testing.FixtureNamedBox
import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.namedProductBackfill
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** Runs every case of the repository-wide `fixtures/named-product-backfill.json`, which the Swift core also runs. */
class NamedProductBackfillFixtureTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)

    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        assertTrue(SharedFixtures.namedProductBackfill().cases.size >= 8)
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.namedProductBackfill().cases.map { case ->
        dynamicTest(case.name) {
            val products = NamedProductBackfill.products(
                latest = case.latest.map(::box),
                isKnown = case.known.toSet()::contains,
                ids = UUID::randomUUID,
                now = now
            )
            assertEquals(case.expect, products.map(::fixture))
            assertTrue(products.all { it.createdAt == now && it.updatedAt == now })
            assertEquals(products.size, products.map { it.id }.toSet().size)
        }
    }

    private fun box(fixture: FixtureNamedBox) = Box(
        product = ProductRef(fixture.gtin, fixture.name),
        category = CategoryID(fixture.category),
        categoryOverridden = fixture.categoryOverridden,
        locationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH
    )

    private fun fixture(product: NamedProduct) =
        FixtureNamedBox(product.gtin, product.name, product.category.rawValue, product.categoryOverridden)
}
