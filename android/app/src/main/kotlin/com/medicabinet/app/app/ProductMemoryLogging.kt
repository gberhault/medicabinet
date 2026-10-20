package com.medicabinet.app.app

import com.medicabinet.core.named.MemoryEvent
import com.medicabinet.core.named.ProductMemory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Records [event] after the box was saved. The box is the household's data and the memory a convenience: a failure is
 * logged (the exception class only, never a name or a code) and never rethrown, so it cannot fail the save.
 */
suspend fun ProductMemory.recordOrLog(event: MemoryEvent) {
    try {
        record(event)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        AppLog.memoryFailed(failure)
    }
}
