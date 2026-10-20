package com.medicabinet.app.features.transfer

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Formatting shared by the transfer screens (X5: dates in the user's locale, never hand-built). */
object TransferText {
    /** Date and time, medium style, in the user's locale and time zone (strings.md). */
    fun dateTime(instant: Instant, locale: Locale, zone: ZoneId): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(instant)

    /** The exporting platform as its proper name (`ios` → iOS); an unknown value is shown as written. */
    fun platform(raw: String): String = when (raw) {
        "ios" -> "iOS"
        "android" -> "Android"
        else -> raw
    }
}
