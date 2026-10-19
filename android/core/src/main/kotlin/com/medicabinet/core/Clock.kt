package com.medicabinet.core

import java.time.Duration
import java.time.Instant

/** The only source of "now" for domain code, so date logic is testable. */
fun interface Clock {
    fun now(): Instant
}

/** A clock stuck on one instant that tests can move forward. */
class FixedClock(private var current: Instant) : Clock {
    override fun now(): Instant = current

    fun advance(by: Duration) {
        current = current.plus(by)
    }
}
