package com.medicabinet.app.persistence

import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.ProductRef
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.testing.MutableClock
import com.medicabinet.core.testing.RepositoryContract
import com.medicabinet.core.testing.SharedFixtures
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RobolectricTestRunner

private fun openCabinet() =
    CabinetDatabase(ApplicationProvider.getApplicationContext(), name = "test-${UUID.randomUUID()}.db")

/** Runs the shared repository contract against the real SQLite repositories. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class SqlRepositoryContractTest(private val check: RepositoryContract.Check) {
    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun checks(): List<Array<Any>> = RepositoryContract.checks.map { arrayOf(it) }
    }

    @Test
    fun sqlRepositoriesSatisfyTheContract() = runBlocking {
        val opened = mutableListOf<CabinetDatabase>()
        try {
            check.run { clock ->
                val cabinet = openCabinet().also { opened += it }
                RepositoryContract.Stores(
                    SqlLocationRepository(cabinet, clock),
                    SqlBoxRepository(cabinet, clock),
                    SqlNamedProductRepository(cabinet, clock)
                )
            }
        } finally {
            opened.forEach { it.close() }
        }
    }

    @Test
    fun contractChecksMatchTheSharedList() {
        assertEquals(SharedFixtures.repositoryContractCheckNames(), RepositoryContract.checks.map { it.name })
    }
}

@RunWith(RobolectricTestRunner::class)
class SqlRepositoryStorageTest {
    private val clock = MutableClock(Instant.ofEpochSecond(1_800_000_000))
    private val cabinet = openCabinet()
    private val locations = SqlLocationRepository(cabinet, clock)
    private val boxes = SqlBoxRepository(cabinet, clock)

    @After
    fun close() = cabinet.close()

    private fun box(locationId: UUID, name: String = "Doliprane", expiry: ExpiryDate? = null) =
        Box(product = ProductRef(name = name), locationId = locationId, expiry = expiry, createdAt = clock.now())

    @Test
    fun idsAreStoredAsUpperCaseTextUuids() = runBlocking {
        val location = locations.ensureDefault("Cabinet")
        assertEquals(location.id.toString().uppercase(), SqlProbe.string(cabinet.database, "SELECT id FROM location"))
    }

    @Test
    fun datesAreStoredAsUtcTextLikeOnIos() = runBlocking {
        val location = locations.ensureDefault("Cabinet")
        assertEquals("2027-01-15 08:00:00.000", SqlProbe.string(cabinet.database, "SELECT createdAt FROM location"))
        assertEquals(location.createdAt, locations.defaultLocation()?.createdAt)
    }

    @Test
    fun expiryIsStoredAsPartsAndSortKeyWithMonthPrecisionLeavingTheDayNull() = runBlocking {
        val location = locations.ensureDefault("Cabinet")
        boxes.insert(box(location.id, expiry = ExpiryDate.of(2028, 2, null)))
        val parts = SqlProbe.query(
            cabinet.database,
            "SELECT expiryYear, expiryMonth, expiryDay, expirySortKey FROM box"
        ) {
            listOf(it.getLong(0), it.getLong(1), if (it.isNull(2)) null else it.getLong(2), it.getString(3))
        }.single()
        assertEquals(listOf(2028L, 2L, null, "2028-02-29"), parts)
    }

    @Test
    fun removedBoxesStayInTheTableWithTheirReason() = runBlocking {
        val location = locations.ensureDefault("Cabinet")
        val stored = box(location.id)
        boxes.insert(stored)
        boxes.markRemoved(stored.id, RemovalReason.THROWN_AWAY, clock.now())
        assertEquals("thrownAway", SqlProbe.string(cabinet.database, "SELECT removalReason FROM box"))
    }

    @Test
    fun aCorruptStoredExpiryIsReportedNotRepaired() {
        runBlocking {
            val location = locations.ensureDefault("Cabinet")
            val stored = box(location.id)
            boxes.insert(stored)
            // A year outside the supported range passes the SQL constraints but is not a valid ExpiryDate.
            cabinet.database.execSQL("UPDATE box SET expiryYear = 1850, expiryMonth = 1, expirySortKey = '1850-01-31'")
            val failure = assertFailsWith<PersistenceException.CorruptRow> { boxes.box(stored.id) }
            assertEquals("box", failure.table)
        }
    }

    @Test
    fun aFailedInsertLeavesNothingBehind() = runBlocking {
        val location = locations.ensureDefault("Cabinet")
        assertFailsWith<SQLiteException> { boxes.insert(box(location.id, name = "x".repeat(201))) }
        assertEquals(0L, SqlProbe.long(cabinet.database, "SELECT COUNT(*) FROM box"))
        assertEquals(emptyList(), boxes.activeBoxes())
    }
}
