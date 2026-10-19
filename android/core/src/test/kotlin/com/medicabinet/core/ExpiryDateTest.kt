package com.medicabinet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kotlin-specific behaviour; the validation rules themselves are covered by the shared fixture test. */
class ExpiryDateTest {
    @Test
    fun dayPrecisionKeepsItsParts() {
        val date = ExpiryDate.of(2027, 3, 15)
        assertEquals(2027, date.year)
        assertEquals(3, date.month)
        assertEquals(15, date.day)
        assertFalse(date.isMonthPrecision)
    }

    @Test
    fun monthPrecisionHasNoDay() {
        val date = ExpiryDate.of(2027, 4, null)
        assertNull(date.day)
        assertTrue(date.isMonthPrecision)
        assertEquals(30, date.lastValidDay)
    }

    @Test
    fun invalidInputThrowsWithTheTypedError() {
        val thrown = assertFailsWith<InvalidExpiryDateException> { ExpiryDate.of(2027, 2, 29) }
        assertEquals(ExpiryDateError.InvalidDay(29, month = 2, year = 2027), thrown.error)
    }

    @Test
    fun validateReturnsNullForAValidDate() {
        assertNull(ExpiryDate.validate(2028, 2, 29))
    }

    @Test
    fun equalityUsesAllThreeParts() {
        assertEquals(ExpiryDate.of(2027, 6, 30), ExpiryDate.of(2027, 6, 30))
        assertEquals(ExpiryDate.of(2027, 6, 30).hashCode(), ExpiryDate.of(2027, 6, 30).hashCode())
        assertNotEquals(ExpiryDate.of(2027, 6, 30), ExpiryDate.of(2027, 6, null))
    }

    @Test
    fun monthPrecisionSortsAfterEarlierDaysOfTheSameMonth() {
        assertTrue(ExpiryDate.of(2027, 6, 10) < ExpiryDate.of(2027, 6, null))
        assertEquals(0, ExpiryDate.of(2027, 6, 30).compareTo(ExpiryDate.of(2027, 6, null)))
    }

    @Test
    fun toStringIsReadableAndShowsPrecision() {
        assertEquals("2027-03-15", ExpiryDate.of(2027, 3, 15).toString())
        assertEquals("2027-03", ExpiryDate.of(2027, 3, null).toString())
    }
}
