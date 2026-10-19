package com.medicabinet.core

import java.time.Instant

/** The one place that reads the system time (the architecture check exempts this file). */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
