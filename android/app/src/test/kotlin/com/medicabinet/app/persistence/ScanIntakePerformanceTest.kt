package com.medicabinet.app.persistence

import androidx.test.core.app.ApplicationProvider
import com.medicabinet.core.scan.ExpiryReading
import com.medicabinet.core.scan.IntakeOutcome
import com.medicabinet.core.scan.ProductLookup
import com.medicabinet.core.scan.ReferenceProduct
import com.medicabinet.core.scan.ScanIntake
import com.medicabinet.core.scan.ScannedCode
import com.medicabinet.core.scan.Symbology
import com.medicabinet.core.testing.MutableClock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The scan path must confirm a box in under 300 ms; the data part (evaluate + add) gets a third of that. */
@RunWith(RobolectricTestRunner::class)
class ScanIntakePerformanceTest {
    private object NoLookup : ProductLookup {
        override suspend fun product(gtin: String): ReferenceProduct? = null
    }

    private fun gtin(index: Int) = "0340093" + index.toString().padStart(7, '0')

    @Test
    fun evaluatingAndAddingABoxOnAThousandBoxCabinetStaysUnderTheBudget() = runBlocking {
        val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
        CabinetDatabase(
            ApplicationProvider.getApplicationContext(),
            name = "perf-${UUID.randomUUID()}.db"
        ).use { cabinet ->
            val location = SqlLocationRepository(cabinet, clock).ensureDefault("Cabinet")
            val stamp = StoredDate.write(clock.now())
            cabinet.transaction { db ->
                repeat(1_000) { index ->
                    db.execSQL(
                        "INSERT INTO box (id, gtin, productName, locationId, serial, createdAt, updatedAt) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            UUID.randomUUID().toString(),
                            gtin(index),
                            "Product $index",
                            location.id.stored(),
                            "S$index",
                            stamp,
                            stamp
                        )
                    )
                }
            }
            val intake = ScanIntake(SqlBoxRepository(cabinet, clock), NoLookup, clock)

            val millis = (0 until 10).map { run ->
                val code = ScannedCode(gtin(500 + run), ExpiryReading.Missing, "L", "NEW$run", Symbology.DATA_MATRIX)
                val started = System.nanoTime()
                val outcome = assertIs<IntakeOutcome.NeedsCompletion>(intake.evaluate(code, emptySet()))
                intake.add(outcome.draft.copy(name = "Name"), location.id)
                (System.nanoTime() - started) / 1_000_000
            }.sorted()
            val median = millis[millis.size / 2]
            assertTrue(median < 100, "median $median ms (runs: $millis)")
        }
    }
}
