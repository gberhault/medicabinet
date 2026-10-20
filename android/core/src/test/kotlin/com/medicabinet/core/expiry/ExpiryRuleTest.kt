package com.medicabinet.core.expiry

import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ExpiryRuleTest {
    @Test
    fun storedWindowsMapToTheirChoice() {
        assertEquals(ExpiryWindow.DAYS_30, ExpiryWindow.fromStoredDays(30))
        assertEquals(ExpiryWindow.DAYS_60, ExpiryWindow.fromStoredDays(60))
        assertEquals(ExpiryWindow.DAYS_90, ExpiryWindow.fromStoredDays(90))
    }

    @Test
    fun missingOrUnknownStoredWindowsFallBackToTheDefault() {
        assertEquals(ExpiryWindow.DAYS_60, ExpiryWindow.DEFAULT)
        assertEquals(ExpiryWindow.DEFAULT, ExpiryWindow.fromStoredDays(null))
        for (bad in listOf(0, 45, -30, 61, 365, Int.MAX_VALUE)) {
            assertEquals(ExpiryWindow.DEFAULT, ExpiryWindow.fromStoredDays(bad))
        }
    }

    @Test
    fun windowDaysMatchTheChoices() {
        assertEquals(listOf(30, 60, 90), ExpiryWindow.entries.map { it.days })
    }

    @Test
    fun expiresTodayOnlyOnTheExpiryDay() {
        val expiry = ExpiryDate.of(2027, 3, 15)
        fun assess(day: Int) = ExpiryRule.assess(expiry, CalendarDay.of(2027, 3, day), ExpiryWindow.DAYS_30)
        assertTrue(assess(15).expiresToday)
        assertFalse(assess(14).expiresToday)
        assertFalse(assess(16).expiresToday)
        assertFalse(ExpiryRule.assess(null, CalendarDay.of(2027, 3, 15), ExpiryWindow.DAYS_30).expiresToday)
    }

    @Test
    fun anUnknownExpiryIsNeverOk() {
        val today = CalendarDay.of(2027, 3, 15)
        for (window in ExpiryWindow.entries) {
            val assessment = ExpiryRule.assess(null, today, window)
            assertEquals(ExpiryStatus.UNKNOWN, assessment.status)
            assertNull(assessment.daysLeft)
        }
    }
}
