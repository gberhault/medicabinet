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

/** Schema v3: the location tombstone (`deletedAt`). */
@RunWith(RobolectricTestRunner::class)
class SchemaV3Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "v3-${UUID.randomUUID()}.db"
    private val opened = mutableListOf<CabinetDatabase>()

    @After
    fun closeAll() = opened.forEach { it.close() }

    private fun open(migrations: List<Migration> = Migrations.all): SupportSQLiteDatabase =
        CabinetDatabase(context, name, migrations).also { opened += it }.database

    private fun SupportSQLiteDatabase.location(
        id: String,
        locationName: String,
        isDefault: Int = 0,
        deletedAt: String? = null
    ) = execSQL(
        "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt" +
            (if (deletedAt != null) ", deletedAt" else "") + ") VALUES ('$id', '$locationName', $isDefault, 0, " +
            "'2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000'" +
            (if (deletedAt != null) ", '$deletedAt'" else "") + ")"
    )

    @Test
    fun v3AddsANullableDeletedAtColumnToLocations() {
        val db = open()
        db.location("A", "Cabinet")
        db.location("B", "Garage", deletedAt = "2027-02-01 00:00:00.000")
        val column = SqlProbe.snapshot(db).tables.getValue("location").columns.single { it.name == "deletedAt" }
        assertFalse(column.notNull)
        assertEquals(1L, SqlProbe.long(db, "SELECT COUNT(*) FROM location WHERE deletedAt IS NULL"))
        assertEquals(1L, SqlProbe.long(db, "SELECT COUNT(*) FROM location WHERE deletedAt IS NOT NULL"))
    }

    @Test
    fun theSingleDefaultIndexIsUnchanged() {
        val db = open()
        db.location("A", "One", isDefault = 1)
        assertFailsWith<SQLiteException> {
            db.location("B", "Two", isDefault = 1, deletedAt = "2027-02-01 00:00:00.000")
        }
    }

    @Test
    fun aDeletedLocationKeepsItsRemovedBoxesThroughTheForeignKey() {
        val db = open()
        db.location("A", "Cabinet", isDefault = 1)
        db.location("B", "Garage", deletedAt = "2027-02-01 00:00:00.000")
        db.execSQL(
            "INSERT INTO box (id, productName, locationId, removalReason, removedAt, createdAt, updatedAt) " +
                "VALUES ('X', 'Doliprane', 'B', 'usedUp', '2027-01-02 00:00:00.000', " +
                "'2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
        )
        assertEquals("B", SqlProbe.string(db, "SELECT locationId FROM box WHERE id = 'X'"))
    }

    @Test
    fun upgradingAV2CabinetKeepsEveryLocationAndBox() {
        val before = open(listOf(Migrations.v1, Migrations.v2))
        before.location("A", "Cabinet", isDefault = 1)
        before.execSQL(
            "INSERT INTO box (id, productName, locationId, createdAt, updatedAt) " +
                "VALUES ('X', 'Doliprane', 'A', '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
        )
        opened.forEach { it.close() }
        opened.clear()

        val after = open()
        assertEquals("Cabinet", SqlProbe.string(after, "SELECT name FROM location"))
        assertEquals(0L, SqlProbe.long(after, "SELECT COUNT(*) FROM location WHERE deletedAt IS NOT NULL"))
        assertEquals("X", SqlProbe.string(after, "SELECT id FROM box"))
        assertEquals(3L, SqlProbe.long(after, "PRAGMA user_version"))
    }
}
