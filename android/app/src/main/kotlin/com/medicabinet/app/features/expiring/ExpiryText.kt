package com.medicabinet.app.features.expiring

import android.text.format.DateFormat
import com.medicabinet.core.ExpiryDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A date as the Expiring view says it, in the user's locale: no hand-built pattern, never an invented day. */
object ExpiryText {
    /** The month and year as the locale writes them: skeleton `MMMMyyyy` (e.g. "March 2027", "mars 2027"). */
    private const val MONTH_YEAR_SKELETON = "MMMMyyyy"

    /**
     * A printed day as a long date ("March 15, 2027"); a month-precise expiry (GS1 `DD = 00`) as the month and the
     * year only ("March 2027").
     */
    fun date(expiry: ExpiryDate, locale: Locale): String {
        val day = expiry.day
        return if (day != null) {
            LocalDate.of(expiry.year, expiry.month, day)
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
        } else {
            val pattern = DateFormat.getBestDateTimePattern(locale, MONTH_YEAR_SKELETON)
            LocalDate.of(expiry.year, expiry.month, 1).format(DateTimeFormatter.ofPattern(pattern, locale))
        }
    }
}
