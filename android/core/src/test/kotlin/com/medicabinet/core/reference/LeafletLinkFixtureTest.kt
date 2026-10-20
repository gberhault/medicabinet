package com.medicabinet.core.reference

import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.leafletLinks
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs every case of `fixtures/leaflet-links.json`, which the Swift core also runs. */
class LeafletLinkFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.leafletLinks()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 15, "expected at least 15 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.leafletLinks().cases.map { case ->
        dynamicTest(case.name) {
            val product = case.product?.let { ReferenceProduct("X", cis = it.cis, isWithdrawn = it.isWithdrawn) }
            assertEquals(case.expect, LeafletLink.address(product))
        }
    }
}

class LeafletLinkTest {
    @Test
    fun anAddressIsAlwaysAnAbsoluteHttpsUrlOnTheBdpmHost() {
        val address = assertNotNullAddress(ReferenceProduct("X", cis = "60234100"))
        val uri = URI(address)
        assertEquals("https", uri.scheme)
        assertEquals(LeafletLink.HOST, uri.host)
        assertEquals("/medicament/60234100/extrait", uri.path)
    }

    @Test
    fun aProductBuiltWithoutACisGivesNoLink() {
        assertNull(LeafletLink.address(ReferenceProduct("X")))
    }

    private fun assertNotNullAddress(product: ReferenceProduct): String =
        LeafletLink.address(product) ?: error("expected an address")
}
