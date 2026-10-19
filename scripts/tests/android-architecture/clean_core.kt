package com.medicabinet.core

// A comment may mention Instant.now() and import android.content.Context without triggering a rule.
import java.time.Instant

fun later(clock: () -> Instant): Instant = clock()
