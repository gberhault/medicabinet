package com.medicabinet.core.named

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProduct
import com.medicabinet.core.ProductRef
import com.medicabinet.core.testing.FixtureNamedBox
import com.medicabinet.core.testing.ProductMemoryCase
import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.productMemory
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** Runs every case of the repository-wide `fixtures/product-memory.json`, which the Swift core also runs. */
class MemoryPolicyFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndCoversBothActions() {
        val cases = SharedFixtures.productMemory().cases
        assertTrue(cases.size >= 20)
        assertEquals(setOf("none", "remember"), cases.map { it.expect.action }.toSet())
        assertEquals(setOf("added", "changed", "sessionCorrected"), cases.map { it.event.kind }.toSet())
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.productMemory().cases.map { case ->
        dynamicTest(case.name) {
            val current = case.current?.let {
                NamedProduct(
                    gtin = case.event.after.gtin ?: "03401900000003",
                    name = it.name,
                    category = CategoryID(it.category),
                    categoryOverridden = it.categoryOverridden,
                    createdAt = Instant.EPOCH
                )
            }
            val decision = MemoryPolicy.decide(event(case.event), case.codeKnown, current)
            val expected = when (case.expect.action) {
                "remember" -> MemoryDecision.Remember(
                    requireNotNull(case.expect.name),
                    CategoryID(requireNotNull(case.expect.category)),
                    requireNotNull(case.expect.categoryOverridden)
                )
                else -> MemoryDecision.None
            }
            assertEquals(expected, decision)
        }
    }

    private fun event(fixture: ProductMemoryCase.Event): MemoryEvent {
        val after = box(fixture.after)
        val before = fixture.before?.let(::box)
        return when (fixture.kind) {
            "added" -> MemoryEvent.Added(after)
            "changed" -> MemoryEvent.Changed(requireNotNull(before), after)
            "sessionCorrected" -> MemoryEvent.SessionCorrected(requireNotNull(before), after)
            else -> error("unknown event kind ${fixture.kind}")
        }
    }

    private fun box(fixture: FixtureNamedBox) = Box(
        product = ProductRef(fixture.gtin, fixture.name),
        category = CategoryID(fixture.category),
        categoryOverridden = fixture.categoryOverridden,
        locationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH
    )
}
