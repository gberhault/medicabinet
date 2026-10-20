package com.medicabinet.core.cabinet

import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ExpiryStatus
import com.medicabinet.core.ProductRef
import com.medicabinet.core.testing.CabinetRowsCase
import com.medicabinet.core.testing.SharedFixtures
import com.medicabinet.core.testing.cabinetRows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Runs every case of `fixtures/cabinet-rows.json`, which the Swift core also runs. */
class CabinetRowsFixtureTest {
    @Test
    fun theSharedFixtureLoadsAndHasCases() {
        val fixture = SharedFixtures.cabinetRows()
        assertEquals(1, fixture.formatVersion)
        assertTrue(fixture.cases.size >= 20, "expected at least 20 cases, found ${fixture.cases.size}")
    }

    @TestFactory
    fun caseMatchesTheSharedExpectation(): List<DynamicTest> = SharedFixtures.cabinetRows().cases.map { case ->
        dynamicTest(case.name) { run(case) }
    }

    private fun run(case: CabinetRowsCase) {
        val locations = HashMap<String, UUID>()
        fun location(label: String): UUID =
            locations.getOrPut(label) { UUID.fromString("10000000-0000-0000-0000-%012d".format(locations.size + 1)) }

        val statuses = HashMap<UUID, ExpiryStatus>()
        val boxes = case.boxes.map { fixtureBox ->
            Box(
                id = boxId(fixtureBox.id),
                product = ProductRef(fixtureBox.gtin, fixtureBox.name),
                category = CategoryID(fixtureBox.category),
                locationId = location(fixtureBox.location),
                expiry = fixtureBox.expiry?.let { ExpiryDate.of(it.year, it.month, it.day) },
                createdAt = Instant.ofEpochSecond(fixtureBox.createdOrder.toLong())
            ).also { statuses[it.id] = status(fixtureBox.status) }
        }
        val filter = CabinetFilter(
            query = case.filter.query,
            locationId = case.filter.location?.let(::location),
            status = case.filter.status?.let(::status)
        )

        val snapshot = CabinetRows.build(boxes, filter, case.categoryOrder.map(::CategoryID)) {
            statuses[it.id] ?: ExpiryStatus.UNKNOWN
        }

        val actual = snapshot.sections.map { section ->
            CabinetRowsCase.FixtureSection(
                section.category.rawValue,
                section.rows.map { row ->
                    CabinetRowsCase.FixtureRow(
                        row.name,
                        row.boxes.map { label(it.id) },
                        row.status.rawValue,
                        row.earliestExpiry?.let { CabinetRowsCase.FixtureExpiry(it.year, it.month, it.day) }
                    )
                }
            )
        }
        assertEquals(case.expect.sections, actual)
        assertEquals(case.expect.boxCount, snapshot.boxCount)
    }

    /** `bN` → UUID ending in N, so id order is numeric (the fixture's tie-break rule). */
    private fun boxId(label: String): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(label.drop(1).toInt()))

    private fun label(id: UUID): String = "b" + id.toString().takeLast(ID_DIGITS).toInt()

    private fun status(raw: String): ExpiryStatus = ExpiryStatus.entries.single { it.rawValue == raw }

    private companion object {
        const val ID_DIGITS = 12
    }
}
