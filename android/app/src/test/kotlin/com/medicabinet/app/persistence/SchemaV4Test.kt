package com.medicabinet.app.persistence

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Schema v4: the named products and the backfill marker (`namedProduct`, `cabinetMeta`). */
@RunWith(RobolectricTestRunner::class)
class SchemaV4Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<CabinetDatabase>()

    @After
    fun closeAll() = opened.forEach { it.close() }

    private fun open(name: String, migrations: List<Migration> = Migrations.all): SupportSQLiteDatabase =
        CabinetDatabase(context, name, migrations).also { opened += it }.database

    private fun SupportSQLiteDatabase.named(
        id: String,
        gtin: String = "03401900000003",
        name: String = "Arnica",
        deletedAt: String? = null
    ) = execSQL(
        "INSERT INTO namedProduct (id, gtin, name, createdAt, updatedAt, deletedAt) VALUES " +
            "('$id', '$gtin', '$name', '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000', " +
            (if (deletedAt != null) "'$deletedAt'" else "NULL") + ")"
    )

    @Test
    fun aNewDatabaseHasEmptyNamedProductsAndAPendingBackfill() {
        val db = open("v4-${UUID.randomUUID()}.db")
        assertEquals(0L, SqlProbe.long(db, "SELECT COUNT(*) FROM namedProduct"))
        assertEquals("pending", SqlProbe.string(db, "SELECT value FROM cabinetMeta WHERE key = 'namedProductBackfill'"))
    }

    @Test
    fun upgradingAV3CabinetKeepsEveryLocationAndBoxAndMarksTheBackfillPending() {
        val name = "v3-to-v4-${UUID.randomUUID()}.db"
        open(name, listOf(Migrations.v1, Migrations.v2, Migrations.v3)).let { v3 ->
            v3.execSQL(
                "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) VALUES " +
                    "('L', 'Cabinet', 1, 0, '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
            )
            v3.execSQL(
                "INSERT INTO box (id, gtin, productName, locationId, createdAt, updatedAt) VALUES " +
                    "('B', '03401900000003', 'Arnica', 'L', '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
            )
        }
        opened.forEach { it.close() }
        opened.clear()
        val db = open(name)
        assertEquals(1L, SqlProbe.long(db, "SELECT COUNT(*) FROM location"))
        assertEquals("Arnica", SqlProbe.string(db, "SELECT productName FROM box WHERE id = 'B'"))
        assertEquals(0L, SqlProbe.long(db, "SELECT COUNT(*) FROM namedProduct"))
        assertEquals("pending", SqlProbe.string(db, "SELECT value FROM cabinetMeta WHERE key = 'namedProductBackfill'"))
    }

    @Test
    fun aGtinMustBeFourteenCharactersAndANameOneToTwoHundred() {
        val db = open("v4-${UUID.randomUUID()}.db")
        assertFailsWith<SQLiteException> { db.named("A", gtin = "3401900000003") }
        assertFailsWith<SQLiteException> { db.named("B", name = "") }
        assertFailsWith<SQLiteException> { db.named("C", name = "x".repeat(201)) }
        db.named("D", name = "x".repeat(200))
    }

    @Test
    fun onlyOneLiveNamedProductPerGtinButAForgottenOneMayCoexist() {
        val db = open("v4-${UUID.randomUUID()}.db")
        db.named("A")
        assertFailsWith<SQLiteException> { db.named("B") }
        db.named("C", deletedAt = "2027-02-01 00:00:00.000")
        db.named("D", deletedAt = "2027-03-01 00:00:00.000")
        db.named("E", gtin = "03401900000010")
        assertEquals(4L, SqlProbe.long(db, "SELECT COUNT(*) FROM namedProduct"))
    }

    @Test
    fun theMetaKeyIsUnique() {
        val db = open("v4-${UUID.randomUUID()}.db")
        assertFailsWith<SQLiteException> {
            db.execSQL("INSERT INTO cabinetMeta (key, value) VALUES ('namedProductBackfill', 'done')")
        }
    }
}
