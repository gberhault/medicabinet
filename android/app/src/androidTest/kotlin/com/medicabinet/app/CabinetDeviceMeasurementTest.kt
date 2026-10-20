package com.medicabinet.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.medicabinet.app.persistence.AppPaths
import com.medicabinet.app.persistence.CabinetDatabase
import com.medicabinet.app.persistence.SqlBoxRepository
import com.medicabinet.app.persistence.SqlLocationRepository
import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.SystemClock
import com.medicabinet.core.cabinet.CabinetFilter
import com.medicabinet.core.cabinet.CabinetRows
import com.medicabinet.core.cabinet.RemovalOutcome
import com.medicabinet.core.cabinet.ScanRemoval
import com.medicabinet.core.expiry.CalendarDay
import com.medicabinet.core.expiry.ExpiryRule
import com.medicabinet.core.expiry.ExpiryWindow
import com.medicabinet.core.reference.Taxonomy
import com.medicabinet.core.scan.ExpiryReading
import com.medicabinet.core.scan.ScannedCode
import com.medicabinet.core.scan.Symbology
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in timing of the cabinet on a real phone (task T28): a 1 000-box cabinet, removal by scan, the list build and the
 * query behind the Cabinet tab. Results go to the instrumentation output (`am instrument -w`).
 *
 * It seeds the default database of the app under test, so it only runs for a package whose id ends with `.perf` (a
 * side-by-side debug build with its own data) and only when started with `-e medicabinet.measure true`. It never runs
 * against the real app.
 */
@RunWith(AndroidJUnit4::class)
class CabinetDeviceMeasurementTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun report(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, value) })
    }

    private fun gtin(index: Int) = "0340093" + index.toString().padStart(7, '0')

    private fun expiryOf(index: Int): ExpiryDate? =
        if (index % UNKNOWN_EVERY == 0) null else ExpiryDate.of(FIRST_YEAR + index % YEARS, 1 + index % MONTHS, null)

    private fun median(values: List<Long>) = values.sorted()[values.size / 2]

    @Test
    fun thousandBoxCabinet() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("medicabinet.measure") == "true")
        assumeTrue("only a .perf build may be seeded", context.packageName.endsWith(".perf"))
        context.deleteDatabase(AppPaths.DATABASE_NAME)
        val clock = SystemClock
        val cabinet = CabinetDatabase(context)
        try {
            val locations = SqlLocationRepository(cabinet, clock)
            val boxes = SqlBoxRepository(cabinet, clock)
            val home = locations.ensureDefault("Cabinet")
            val today = CalendarDay.from(Instant.now(), ZoneId.systemDefault())
            repeat(BOX_COUNT) { index ->
                boxes.insert(
                    Box(
                        product = ProductRef(gtin(index % PRODUCTS), "Product ${index % PRODUCTS}"),
                        category = Taxonomy.ordered[index % CATEGORIES],
                        locationId = home.id,
                        expiry = expiryOf(index),
                        serial = "S$index",
                        createdAt = clock.now()
                    )
                )
            }
            report("seeded", "$BOX_COUNT boxes")

            val queries = (0 until RUNS).map {
                val started = System.nanoTime()
                boxes.activeBoxes()
                (System.nanoTime() - started) / NANOS_PER_MILLI
            }
            report("query_1000_boxes_ms_median", median(queries).toString())

            val all = boxes.activeBoxes()
            val builds = (0 until RUNS).map {
                val started = System.nanoTime()
                CabinetRows.build(all, CabinetFilter(query = "product 1"), Taxonomy.ordered) { box ->
                    ExpiryRule.assess(box.expiry, today, ExpiryWindow.DEFAULT).status
                }
                (System.nanoTime() - started) / NANOS_PER_MILLI
            }
            report("rows_build_1000_boxes_ms_median", median(builds).toString())

            val removal = ScanRemoval(boxes, clock)
            val removals = (0 until RUNS).map { run ->
                val index = FIRST_REMOVED + run
                val gtin = gtin(index % PRODUCTS)
                val code = ScannedCode(gtin, ExpiryReading.Missing, "L", "S$index", Symbology.DATA_MATRIX)
                val started = System.nanoTime()
                check(removal.handle(code, RemovalReason.USED_UP) is RemovalOutcome.Removed)
                (System.nanoTime() - started) / NANOS_PER_MILLI
            }
            report("scan_removal_handle_ms_median", median(removals).toString())
            report("scan_removal_handle_ms_all", removals.joinToString(","))
            // Leave the seeded cabinet in place (minus the removed boxes) for the scroll test done from the shell.
            report("remaining_boxes", boxes.activeBoxes().size.toString())
        } finally {
            cabinet.close()
        }
    }

    private companion object {
        const val BOX_COUNT = 1_000
        const val PRODUCTS = 300
        const val CATEGORIES = 8
        const val UNKNOWN_EVERY = 25
        const val FIRST_YEAR = 2026
        const val YEARS = 3
        const val MONTHS = 12
        const val RUNS = 10
        const val FIRST_REMOVED = 500
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
