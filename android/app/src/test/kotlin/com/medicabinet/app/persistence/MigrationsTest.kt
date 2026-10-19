package com.medicabinet.app.persistence

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val v2 = Migration("v2", "CREATE TABLE later (id INTEGER NOT NULL); CREATE INDEX later_id ON later(id)")
    private fun uniqueName() = "migration-${UUID.randomUUID()}.db"

    private fun CabinetDatabase.userVersion() = SqlProbe.long(database, "PRAGMA user_version")

    @Test
    fun aNewDatabaseRunsEveryMigrationAndRecordsTheVersion() {
        CabinetDatabase(context, uniqueName(), listOf(Migrations.v1, v2)).use { cabinet ->
            assertEquals(2L, cabinet.userVersion())
            assertTrue(SqlProbe.snapshot(cabinet.database).tables.containsKey("later"))
        }
    }

    @Test
    fun upgradingStepByStepGivesTheSameSchemaAsAFreshInstall() {
        val name = uniqueName()
        CabinetDatabase(context, name, listOf(Migrations.v1)).use { it.userVersion() }
        val upgraded = CabinetDatabase(context, name, listOf(Migrations.v1, v2)).use { SqlProbe.snapshot(it.database) }
        val fresh = CabinetDatabase(context, uniqueName(), listOf(Migrations.v1, v2)).use {
            SqlProbe.snapshot(it.database)
        }
        assertEquals(fresh, upgraded)
    }

    @Test
    fun upgradingKeepsTheExistingRows() {
        val name = uniqueName()
        CabinetDatabase(context, name, listOf(Migrations.v1)).use {
            it.database.execSQL(
                "INSERT INTO location (id, name, isDefault, sortOrder, createdAt, updatedAt) " +
                    "VALUES ('L', 'Kept', 0, 0, '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000')"
            )
        }
        CabinetDatabase(context, name, listOf(Migrations.v1, v2)).use {
            assertEquals("Kept", SqlProbe.string(it.database, "SELECT name FROM location"))
        }
    }

    @Test
    fun aDatabaseNewerThanTheAppIsRefusedNotDowngraded() {
        val name = uniqueName()
        CabinetDatabase(context, name, listOf(Migrations.v1, v2)).use { it.userVersion() }
        assertFailsWith<IllegalStateException> {
            CabinetDatabase(context, name, listOf(Migrations.v1)).use { it.database }
        }
    }

    @Test
    fun aFailingMigrationLeavesTheDatabaseExactlyAsItWas() {
        val name = uniqueName()
        CabinetDatabase(context, name, listOf(Migrations.v1)).use { it.userVersion() }
        val broken = Migration("v2", "CREATE TABLE half_done (id INTEGER); THIS IS NOT SQL")
        assertFailsWith<SQLiteException> {
            CabinetDatabase(context, name, listOf(Migrations.v1, broken)).use { it.database }
        }

        CabinetDatabase(context, name, listOf(Migrations.v1)).use { cabinet ->
            assertEquals(1L, cabinet.userVersion())
            assertNull(SqlProbe.string(cabinet.database, "SELECT name FROM sqlite_master WHERE name = 'half_done'"))
        }
    }

    @Test
    fun v1HasEightStatementsAndNoSemicolonInsideALiteralOrComment() {
        assertEquals(8, Migrations.v1.statements.size)
        for (migration in Migrations.all) {
            val withoutTerminators = migration.sql.trim().removeSuffix(";")
            val outsideLiterals = withoutTerminators.replace(Regex("'[^']*'"), "''")
            assertTrue(
                !outsideLiterals.lines().any {
                    it.contains("--") && it.substringAfter("--").contains(";")
                },
                "; in a comment"
            )
            assertTrue(
                !Regex("'[^']*;[^']*'").containsMatchIn(withoutTerminators),
                "${migration.name}: ; inside a string literal"
            )
        }
    }

    @Test
    fun migrationNamesAreUniqueAndOrdered() {
        assertEquals(listOf("v1"), Migrations.all.map { it.name })
        assertEquals(Migrations.all.map { it.name }.toSet().size, Migrations.all.size)
    }
}
