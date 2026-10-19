package com.medicabinet.app.features.scan

import android.content.Context
import com.medicabinet.app.R
import com.medicabinet.core.ExpiryDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** User-facing text for the scan screens: always from the string resources, dates in the user's locale. */
object ScanText {
    /** "Expires March 15, 2027", "Expires end of March 2027" (month precision) or "Expiry unknown". */
    fun expiry(
        context: Context,
        expiry: ExpiryDate?,
        locale: Locale = context.resources.configuration.locales[0]
    ): String = when {
        expiry == null -> context.getString(R.string.scan_added_expiryUnknown)
        expiry.day != null -> {
            val date = LocalDate.of(expiry.year, expiry.month, expiry.day ?: 1)
            val text = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
            context.getString(R.string.scan_added_expiry, text)
        }
        else -> {
            // Month and year in the user's language ("mars 2027" / "March 2027").
            val text = LocalDate.of(
                expiry.year,
                expiry.month,
                1
            ).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            context.getString(R.string.scan_added_expiryMonth, text)
        }
    }
}
