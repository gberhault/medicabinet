package com.medicabinet.app.features.expiring

import com.medicabinet.core.ExpiryDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpiryTextTest {
    @Test
    fun aMonthPreciseDateShowsTheMonthAndYearOnlyInTheLocale() {
        assertEquals("March 2027", ExpiryText.date(ExpiryDate.of(2027, 3, null), Locale.ENGLISH))
        assertEquals("mars 2027", ExpiryText.date(ExpiryDate.of(2027, 3, null), Locale.FRENCH))
    }

    @Test
    fun aMonthPreciseDateNeverInventsADay() {
        listOf(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN).forEach { locale ->
            val text = ExpiryText.date(ExpiryDate.of(2028, 2, null), locale)
            assertTrue(text.contains("2028"), text)
            assertFalse(Regex("\\b(28|29|1)\\b").containsMatchIn(text), "$locale: $text")
        }
    }

    @Test
    fun aPrintedDayShowsTheLongDateInTheLocale() {
        assertEquals("March 15, 2027", ExpiryText.date(ExpiryDate.of(2027, 3, 15), Locale.US))
        assertEquals("15 mars 2027", ExpiryText.date(ExpiryDate.of(2027, 3, 15), Locale.FRANCE))
    }
}
