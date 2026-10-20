package com.medicabinet.core.reference

import com.medicabinet.core.CategoryID
import com.medicabinet.core.testing.InMemoryReferenceData
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ReferenceDataDateTest {
    @Test
    fun parsesARealCalendarDay() {
        assertEquals(ReferenceDataDate(2026, 10, 18), ReferenceDataDate.parse("2026-10-18"))
    }

    @Test
    fun leapDays() {
        assertNotNull(ReferenceDataDate.parse("2028-02-29"))
        assertNull(ReferenceDataDate.parse("2027-02-29"))
    }

    @Test
    fun rejectsAnythingElse() {
        listOf(
            "", "2026-13-01", "2026-00-10", "2026-04-31", "2026-4-1", "20261018", "2026-10-18 ", " 2026-10-18",
            "2026/10/18", "2026-10-1a", "1999-12-31", "2100-01-01", "２０２６-10-18", "2026-10-18-01"
        ).forEach { assertNull(ReferenceDataDate.parse(it), "'$it' must be rejected") }
    }
}

class ReferenceDataSourceTest {
    private val date = ReferenceDataDate(2026, 10, 18)
    private val product = ReferenceProduct("TESTAMOL 500 mg, comprimé", CategoryID.PAIN_FEVER)

    @Test
    fun rawValuesAreThePlatformNeutralNames() {
        assertEquals("missing", ReferenceUnavailableReason.MISSING.rawValue)
        assertEquals("unreadable", ReferenceUnavailableReason.UNREADABLE.rawValue)
        assertEquals("unsupportedSchema", ReferenceUnavailableReason.UNSUPPORTED_SCHEMA.rawValue)
    }

    @Test
    fun aProductIsFoundByItsDataMatrixGtin() = runBlocking {
        val source = InMemoryReferenceData(mapOf("3400900000013" to product), ReferenceStatus.Available(date))
        assertEquals(product, source.product("03400900000013"))
        assertEquals(ReferenceStatus.Available(date), source.status())
    }

    @Test
    fun anUnknownOrMalformedGtinIsUnknown() = runBlocking {
        val source = InMemoryReferenceData(mapOf("3400900000013" to product), ReferenceStatus.Available(date))
        assertNull(source.product("03400900005001"))
        assertNull(source.product("3400900000013"))
        assertNull(source.product(""))
    }

    @Test
    fun anUnavailableSourceKnowsNothingAndSaysWhy() = runBlocking {
        val status = ReferenceStatus.Unavailable(ReferenceUnavailableReason.UNREADABLE)
        val source = InMemoryReferenceData(mapOf("3400900000013" to product), status)
        assertNull(source.product("03400900000013"))
        assertEquals(status, source.status())
    }

    @Test
    fun aReferenceProductDefaultsToNoCategory() {
        assertNull(ReferenceProduct("X").category)
    }
}
