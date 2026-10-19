package com.medicabinet.app.persistence

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Schema v2: the box tombstone (`deletedAt`) and the unique serial among boxes that are not deleted. */
@RunWith(RobolectricTestRunner::class)
class SchemaV2Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "v2-${UUID.randomUUID()}.db"
    private val opened = mutableListOf<CabinetDatabase>()

    @After
    fun closeAll() = opened.forEach { it.close() }

    private fun open(migrations: List<Migration> = Migrations.all): SupportSQLiteDatabase =
        CabinetDatabase(context, name, migrations).also { opened += it }.database

    private fun SupportSQLiteDatabase.location() = execSQL(
        "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) " +
            "VALUES ('L', 'Cabinet', 0, 0, '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
    )

    private fun SupportSQLiteDatabase.box(id: String, gtin: String?, serial: String?, deletedAt: String? = null) =
        execSQL(
            "INSERT INTO box (id, gtin, productName, locationId, serial, createdAt, updatedAt" +
                (if (deletedAt != null) ", deletedAt" else "") + ") VALUES ('$id', ${gtin.sql()}, 'Doliprane', 'L', " +
                "${serial.sql()}, '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000'" +
                (if (deletedAt != null) ", '$deletedAt'" else "") + ")"
        )

    private fun String?.sql() = if (this == null) "NULL" else "'$this'"

    private fun SupportSQLiteDatabase.boxIds() = query("SELECT id FROM box ORDER BY id").mapRows { getString(0) }

    @Test
    fun theSameGtinAndSerialCannotBeStoredTwice() {
        val db = open()
        db.location()
        db.box("A", "03400930000001", "S1")
        assertFailsWith<SQLiteException> { db.box("B", "03400930000001", "S1") }
    }

    @Test
    fun aTombstoneFreesTheSerialAndOtherCombinationsAreAllowed() {
        val db = open()
        db.location()
        db.box("A", "03400930000001", "S1", deletedAt = "2027-01-02 00:00:00.000")
        db.box("B", "03400930000001", "S1")
        db.box("C", "03400930000002", "S1")
        db.box("D", "03400930000001", "S2")
        db.box("E", "03400930000001", null)
        db.box("F", "03400930000001", null)
        db.box("G", null, "S1")
        db.box("H", null, "S1")
        assertEquals(8, db.boxIds().size)
    }

    @Test
    fun upgradingAV1CabinetKeepsEveryRow() {
        val v1 = open(listOf(Migrations.v1))
        v1.location()
        v1.execSQL(
            "INSERT INTO box (id, gtin, productName, locationId, serial, createdAt, updatedAt) VALUES " +
                "('A', '03400930000001', 'x', 'L', 'S1', '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000'), " +
                "('B', '03400930000002', 'x', 'L', 'S1', '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
        )
        opened.forEach { it.close() }
        opened.clear()

        val db = open()
        assertEquals(listOf("A", "B"), db.boxIds())
        assertEquals(0L, SqlProbe.long(db, "SELECT COUNT(*) FROM box WHERE deletedAt IS NOT NULL"))
        assertEquals(2L, SqlProbe.long(db, "PRAGMA user_version"))
    }

    @Test
    fun aV1CabinetWithDuplicateSerialsFailsToUpgradeAndStaysOnV1() {
        val v1 = open(listOf(Migrations.v1))
        v1.location()
        v1.execSQL(
            "INSERT INTO box (id, gtin, productName, locationId, serial, createdAt, updatedAt) VALUES " +
                "('A', '03400930000001', 'x', 'L', 'S1', '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000'), " +
                "('B', '03400930000001', 'x', 'L', 'S1', '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
        )
        opened.forEach { it.close() }
        opened.clear()

        assertFailsWith<SQLiteException> { open() }

        val after = open(listOf(Migrations.v1))
        assertEquals(listOf("A", "B"), after.boxIds())
        assertEquals(1L, SqlProbe.long(after, "PRAGMA user_version"))
        assertFalse(SqlProbe.snapshot(after).tables.getValue("box").columns.any { it.name == "deletedAt" })
    }
}
