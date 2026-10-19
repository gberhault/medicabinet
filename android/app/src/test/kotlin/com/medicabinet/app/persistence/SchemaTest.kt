package com.medicabinet.app.persistence

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.core.testing.SharedFixtures
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SchemaTest {
    private lateinit var cabinet: CabinetDatabase
    private val db: SupportSQLiteDatabase get() = cabinet.database

    @Before
    fun openDatabase() {
        cabinet = CabinetDatabase(ApplicationProvider.getApplicationContext(), name = "test-${UUID.randomUUID()}.db")
    }

    @After
    fun closeDatabase() = cabinet.close()

    private fun location(id: String = "L", name: String = "Cabinet", isDefault: Int = 0) = db.execSQL(
        "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) " +
            "VALUES ('$id', '$name', $isDefault, 0, '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
    )

    private fun box(
        location: String = "L",
        gtin: String? = "03400930000001",
        expiry: Triple<Int?, Int?, String?> = Triple(null, null, null),
        day: Int? = null,
        removal: Pair<String?, String?> = null to null,
        name: String = "Doliprane"
    ) = db.execSQL(
        "INSERT INTO box (id, gtin, productName, locationId, expiryYear, expiryMonth, expiryDay, expirySortKey, " +
            "removalReason, removedAt, createdAt, updatedAt) VALUES ('${UUID.randomUUID()}', ${gtin.sql()}, '$name', " +
            "'$location', ${expiry.first.sql()}, ${expiry.second.sql()}, ${day.sql()}, ${expiry.third.sql()}, " +
            "${removal.first.sql()}, ${removal.second.sql()}, '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
    )

    private fun Any?.sql(): String = when (this) {
        null -> "NULL"
        is String -> "'$this'"
        else -> toString()
    }

    // --- shape: identical to iOS ---

    @Test
    fun theMigratedDatabaseMatchesTheSharedSchemaFixtureExactly() {
        assertEquals(SharedFixtures.schemaV1(), SqlProbe.snapshot(db))
    }

    @Test
    fun theV1SqlEqualsTheSharedSqlFixture() {
        assertEquals(normalized(SharedFixtures.text("schema-v1.sql")), normalized(Migrations.v1.sql))
    }

    @Test
    fun aChangedStatementDoesNotEqualTheSharedSqlFixture() {
        val altered = Migrations.v1.sql.replace("BETWEEN 1 AND 100", "BETWEEN 1 AND 99")
        assertNotEquals(normalized(SharedFixtures.text("schema-v1.sql")), normalized(altered))
    }

    /** Whitespace and `--` comment lines are not part of the comparison (same rule as the iOS test). */
    private fun normalized(sql: String): String = sql.lines().filterNot {
        it.trim().startsWith("--")
    }.joinToString(" ").trim().split(Regex("\\s+")).joinToString(" ")

    @Test
    fun aFixtureWithAMissingIndexDoesNotMatch() {
        val fixture = SharedFixtures.schemaV1()
        val altered = fixture.copy(indexes = fixture.indexes.filterNot { it.name == "box_gtin" })
        assertNotEquals(altered, SqlProbe.snapshot(db))
    }

    @Test
    fun theFixtureCoversBothTablesAndEveryLookupIndex() {
        val fixture = SharedFixtures.schemaV1()
        assertEquals(setOf("location", "box"), fixture.tables.keys)
        assertEquals(
            setOf("location_single_default", "box_gtin", "box_location", "box_expiry", "box_active", "box_serial"),
            fixture.indexes.map { it.name }.toSet()
        )
        assertEquals("serial IS NOT NULL", fixture.indexes.first { it.name == "box_serial" }.where)
        assertTrue(fixture.tables.getValue("box").columns.any { it.type == "DATETIME" })
    }

    @Test
    fun foreignKeysAreEnforcedAndWriteAheadLoggingIsOn() {
        assertEquals(1L, SqlProbe.long(db, "PRAGMA foreign_keys"))
        assertEquals("wal", SqlProbe.string(db, "PRAGMA journal_mode")?.lowercase())
    }

    // --- constraints (mirror the iOS MigrationTests) ---

    @Test
    fun aValidBoxIsAccepted() {
        location()
        box(expiry = Triple(2027, 3, "2027-03-15"), day = 15)
        box(expiry = Triple(2027, 3, "2027-03-31"))
        box()
        assertEquals(3L, SqlProbe.long(db, "SELECT COUNT(*) FROM box"))
    }

    @Test
    fun anEmptyLocationNameIsRejected() {
        assertFailsWith<SQLiteException> { location(name = "") }
    }

    @Test
    fun aSecondDefaultLocationIsRejectedButManyNonDefaultOnesAreFine() {
        location(id = "A", name = "A", isDefault = 1)
        assertFailsWith<SQLiteException> { location(id = "B", name = "B", isDefault = 1) }
        location(id = "C", name = "C")
        location(id = "D", name = "D")
    }

    @Test
    fun invalidExpiryPartsAreRejected() {
        location()
        assertFailsWith<SQLiteException> { box(expiry = Triple(2027, 13, "2027-13-01")) }
        assertFailsWith<SQLiteException> { box(expiry = Triple(2027, 0, "2027-00-01")) }
        assertFailsWith<SQLiteException> { box(expiry = Triple(2027, 1, "2027-01-32"), day = 32) }
    }

    @Test
    fun halfFilledExpiryIsRejected() {
        location()
        assertFailsWith<SQLiteException> { box(expiry = Triple(2027, null, "2027-01-01")) }
        assertFailsWith<SQLiteException> { box(expiry = Triple(null, 3, null)) }
        assertFailsWith<SQLiteException> { box(expiry = Triple(2027, 3, null)) }
    }

    @Test
    fun removalReasonAndDateMustBeSetTogetherAndBeKnown() {
        location()
        assertFailsWith<SQLiteException> { box(removal = "usedUp" to null) }
        assertFailsWith<SQLiteException> { box(removal = null to "2027-01-01 00:00:00.000") }
        assertFailsWith<SQLiteException> { box(removal = "lost" to "2027-01-01 00:00:00.000") }
        box(removal = "thrownAway" to "2027-01-01 00:00:00.000")
    }

    @Test
    fun gtinMustBeFourteenDigitsAndNamesAreBounded() {
        location()
        assertFailsWith<SQLiteException> { box(gtin = "3400930000001") }
        assertFailsWith<SQLiteException> { box(name = "x".repeat(201)) }
    }

    @Test
    fun aBoxNeedsAnExistingLocationAndALocationWithBoxesCannotBeDeleted() {
        assertFailsWith<SQLiteException> { box(location = "missing") }
        location()
        box()
        assertFailsWith<SQLiteException> { db.execSQL("DELETE FROM location WHERE id = 'L'") }
    }

    // --- indexes are used by the hot queries ---

    private fun planOf(sql: String): String =
        SqlProbe.query(db, "EXPLAIN QUERY PLAN $sql") { it.getString(3) }.joinToString(" | ")

    @Test
    fun hotQueriesUseAnIndex() {
        mapOf(
            "SELECT * FROM box WHERE removedAt IS NULL" to "box_active",
            "SELECT * FROM box WHERE gtin = '03400930000001'" to "box_gtin",
            "SELECT * FROM box WHERE locationId = 'L'" to "box_location",
            "SELECT * FROM box ORDER BY expirySortKey" to "box_expiry"
        ).forEach { (sql, index) -> assertContains(planOf(sql), index, message = "plan for [$sql]") }
    }

    @Test
    fun databaseAndBackupsLiveInInternalStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val paths = AppPaths(context)
        val internalRoot = context.dataDir.absolutePath
        assertTrue(paths.databaseFile.absolutePath.startsWith(internalRoot), "database outside internal storage")
        assertTrue(paths.backupsDirectory.absolutePath.startsWith(internalRoot), "backups outside internal storage")
        assertEquals("cabinet.db", paths.databaseFile.name)
    }
}
