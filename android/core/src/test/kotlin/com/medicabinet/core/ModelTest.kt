package com.medicabinet.core

import com.medicabinet.core.testing.SharedFixtures
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)

    @Test
    fun categoryKeyKeepsItsRawValueAndHasAnUncategorizedValue() {
        assertEquals("pain-fever", CategoryID("pain-fever").rawValue)
        assertEquals("uncategorized", CategoryID.UNCATEGORIZED.rawValue)
    }

    @Test
    fun locationDefaultsUpdatedAtToCreatedAtAndHasStableIdentity() {
        val location = Location(name = "Bathroom", createdAt = now)
        assertEquals(now, location.updatedAt)
        assertFalse(location.isDefault)
        assertNotEquals(location.id, Location(name = "Bathroom", createdAt = now).id)
        assertEquals(location.id, Location(id = location.id, name = "X", createdAt = now).id)
    }

    @Test
    fun newBoxIsInTheCabinetWithUnknownExpiryAndUncategorized() {
        val box = Box(product = ProductRef(name = "Doliprane"), locationId = UUID.randomUUID(), createdAt = now)
        assertTrue(box.isInCabinet)
        assertNull(box.expiry)
        assertEquals(CategoryID.UNCATEGORIZED, box.category)
        assertFalse(box.categoryOverridden)
        assertFalse(box.isOpened)
        assertEquals(now, box.updatedAt)
    }

    @Test
    fun boxWithRemovalIsNotInTheCabinet() {
        val removal = Box.Removal(RemovalReason.THROWN_AWAY, now)
        val box = Box(
            product = ProductRef(name = "Doliprane"),
            locationId = UUID.randomUUID(),
            createdAt = now,
            removal = removal
        )
        assertFalse(box.isInCabinet)
        assertEquals(RemovalReason.THROWN_AWAY, box.removal?.reason)
    }

    @Test
    fun boxKeepsAKnownExpiryUnchanged() {
        val expiry = ExpiryDate.of(2027, 2, null)
        val box = Box(
            product = ProductRef(gtin = "03400930000001", name = "A"),
            locationId = UUID.randomUUID(),
            expiry = expiry,
            createdAt = now
        )
        assertEquals(expiry, box.expiry)
        assertEquals("03400930000001", box.product.gtin)
    }

    @Test
    fun expiryStatusHasAnExplicitUnknownCaseThatIsNotOk() {
        assertTrue(ExpiryStatus.entries.contains(ExpiryStatus.UNKNOWN))
        assertNotEquals(ExpiryStatus.UNKNOWN, ExpiryStatus.OK)
    }

    // --- names shared with iOS (fixtures/README.md, "Names that cross platforms") ---

    @Test
    fun crossPlatformRawValuesMatchTheSharedList() {
        assertEquals(listOf("usedUp", "thrownAway"), RemovalReason.entries.map { it.rawValue })
        assertEquals(
            listOf("expired", "expiringSoon", "ok", "unknown"),
            ExpiryStatus.entries.map { it.rawValue }
        )
        assertEquals("uncategorized", CategoryID.UNCATEGORIZED.rawValue)
    }

    @Test
    fun theSharedReadmeListsEveryCrossPlatformName() {
        val readme = SharedFixtures.text("README.md")
        val names = RemovalReason.entries.map { it.rawValue } +
            ExpiryStatus.entries.map { it.rawValue } +
            CategoryID.UNCATEGORIZED.rawValue +
            listOf("invalidYear", "invalidMonth", "invalidDay") +
            listOf("notFound", "unknownLocation", "duplicate", "duplicateDefault", "duplicateSerial")
        for (name in names) {
            assertTrue(readme.contains("`$name`"), "fixtures/README.md does not list `$name`")
        }
    }
}
