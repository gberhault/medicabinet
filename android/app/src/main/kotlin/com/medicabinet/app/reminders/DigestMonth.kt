package com.medicabinet.app.reminders

import com.medicabinet.core.expiry.CalendarDay
import java.util.Locale

/** The `YYYY-MM` of a digest day: what `reminder.lastDigestMonth` stores, the guard against a second digest. */
object DigestMonth {
    fun key(day: CalendarDay): String = String.format(Locale.ROOT, "%04d-%02d", day.year, day.month)
}
