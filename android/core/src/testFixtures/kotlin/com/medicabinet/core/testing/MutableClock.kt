package com.medicabinet.core.testing

import com.medicabinet.core.Clock
import java.time.Duration
import java.time.Instant

/** A clock tests can move forward; shared by reference with the repository under test (thread safe). */
class MutableClock(start: Instant) : Clock {
    @Volatile
    private var current: Instant = start

    @Synchronized
    override fun now(): Instant = current

    @Synchronized
    fun advance(by: Duration) {
        current = current.plus(by)
    }
}
