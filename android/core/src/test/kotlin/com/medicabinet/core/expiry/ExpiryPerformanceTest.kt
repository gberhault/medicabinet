package com.medicabinet.core.expiry

import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.system.measureNanoTime
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Timing budget for a 1 000-box cabinet: the Expiring view and twelve digests together must stay far
 * below a frame. The budget is generous (several times the measured cost) and uses the median of 10 runs.
 */
class ExpiryPerformanceTest {
    private fun cabinet(count: Int): List<Box> {
        val location = UUID.randomUUID()
        val names = listOf("Doliprane", "Éfferalgan", "Smecta", "Spasfon", "Advil", "Nurofen", "Zyrtec", "Gaviscon")
        return (0 until count).map { index ->
            Box(
                product = ProductRef(name = "${names[index % names.size]} $index"),
                locationId = location,
                expiry = if (index % 10 == 0) {
                    null
                } else {
                    ExpiryDate.of(2026 + index % 4, 1 + index % 12, if (index % 3 == 0) null else 1 + index % 28)
                },
                createdAt = Instant.EPOCH,
                removal = if (index % 25 == 0) Box.Removal(RemovalReason.USED_UP, Instant.EPOCH) else null
            )
        }
    }

    @Test
    fun overviewAndTwelveDigestsOnAThousandBoxesStayUnderBudget() {
        val boxes = cabinet(1_000)
        val today = CalendarDay.of(2027, 3, 15)
        val budget = Duration.ofMillis(50)
        repeat(3) { run(boxes, today) } // warm the JIT: the budget is for steady-state cost
        val timings = (0 until 10).map { Duration.ofNanos(measureNanoTime { run(boxes, today) }) }.sorted()
        val median = timings[timings.size / 2]
        assertTrue(median < budget, "median $median exceeds $budget")
    }

    private fun run(boxes: List<Box>, today: CalendarDay) {
        val overview = ExpiringOverview.make(boxes, today, ExpiryWindow.DAYS_60)
        var day = today
        repeat(ExpiryDigest.SCHEDULED_AHEAD) {
            day = day.firstOfNextMonth()
            ExpiryDigest.counts(boxes, day, ExpiryWindow.DAYS_60)
        }
        assertTrue(!overview.isEmpty)
    }
}
