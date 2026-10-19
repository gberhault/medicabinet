package com.medicabinet.core.expiry

import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.expiringOverview
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs every case of `fixtures/expiring-overview.json`, which the Swift core also runs. */
class ExpiringOverviewFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.expiringOverview()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 9, "expected at least 9 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun overviewMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.expiringOverview().cases.map { case ->
        dynamicTest(case.name) {
            val today = assertNotNull(CalendarDay.parse(case.today))
            val window = assertNotNull(ExpiryWindow.entries.firstOrNull { it.days == case.windowDays })
            val location = UUID.randomUUID()
            val boxes = case.boxes.map { it.box(location) }

            val overview = ExpiringOverview.make(boxes, today, window)

            fun ids(entries: List<ExpiringEntry>) = entries.map { it.box.id.toString().lowercase() }
            assertEquals(case.expect.expired, ids(overview.expired))
            assertEquals(case.expect.expiringSoon, ids(overview.expiringSoon))
            assertEquals(case.expect.unknown, ids(overview.unknown))
            assertEquals(case.expect.badge, overview.attentionCount)
            assertEquals(case.expect.isEmpty, overview.isEmpty)
        }
    }
}
