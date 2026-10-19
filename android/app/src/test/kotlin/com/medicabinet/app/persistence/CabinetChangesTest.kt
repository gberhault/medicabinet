package com.medicabinet.app.persistence

import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The change signal the observation flows are built on: one bump per committed transaction. */
@RunWith(RobolectricTestRunner::class)
class CabinetChangesTest {
    private lateinit var cabinet: CabinetDatabase

    @Before
    fun open() {
        cabinet = CabinetDatabase(ApplicationProvider.getApplicationContext(), name = "changes-${UUID.randomUUID()}.db")
        cabinet.database
    }

    @After
    fun close() = cabinet.close()

    private fun insertLocation(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String) = db.execSQL(
        "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) " +
            "VALUES ('$id', 'N$id', 0, 0, '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
    )

    @Test
    fun aCommittedTransactionBumpsTheCounterOnceWhateverItWrites() {
        val before = cabinet.changes.value
        cabinet.transaction {
            insertLocation(it, "A")
            insertLocation(it, "B")
        }
        assertEquals(before + 1, cabinet.changes.value)
    }

    @Test
    fun aRolledBackTransactionLeavesTheCounterAlone() {
        val before = cabinet.changes.value
        assertFailsWith<IllegalStateException> {
            cabinet.transaction {
                insertLocation(it, "A")
                error("boom")
            }
        }
        assertEquals(before, cabinet.changes.value)
    }

    @Test
    fun readingDoesNotBumpTheCounter() {
        val before = cabinet.changes.value
        cabinet.database.query("SELECT COUNT(*) FROM location").use { it.moveToFirst() }
        assertEquals(before, cabinet.changes.value)
    }
}
