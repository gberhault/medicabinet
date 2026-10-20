package com.medicabinet.app.persistence

import androidx.test.core.app.ApplicationProvider
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.cabinet.RemovalOutcome
import com.medicabinet.core.cabinet.ScanRemoval
import com.medicabinet.core.scan.ExpiryReading
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

/** Removal by scan must confirm in under 300 ms; the data part (one lookup + one update) gets a third of that. */
@RunWith(RobolectricTestRunner::class)
class ScanRemovalPerformanceTest {
    private fun gtin(index: Int) = "0340093" + index.toString().padStart(7, '0')

    @Test
    fun handlingARemovalOnAThousandBoxCabinetStaysUnderTheBudget() = runBlocking {
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
                            UUID.randomUUID().stored(),
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
            val removal = ScanRemoval(SqlBoxRepository(cabinet, clock), clock)

            val millis = (0 until 10).map { run ->
                val index = 500 + run
                val code = ScannedCode(gtin(index), ExpiryReading.Missing, "L", "S$index", Symbology.DATA_MATRIX)
                val started = System.nanoTime()
                assertIs<RemovalOutcome.Removed>(removal.handle(code, RemovalReason.USED_UP))
                (System.nanoTime() - started) / 1_000_000
            }.sorted()
            val median = millis[millis.size / 2]
            assertTrue(median < 100, "median $median ms (runs: $millis)")
        }
    }
}
