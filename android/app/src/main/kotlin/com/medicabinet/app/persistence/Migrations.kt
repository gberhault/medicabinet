package com.medicabinet.app.persistence

import androidx.sqlite.db.SupportSQLiteDatabase

/** One schema step. Applying migrations `0 until n` gives schema version `n` (`PRAGMA user_version`). */
class Migration(val name: String, val sql: String) {
    /**
     * The statements of [sql], split on `;`. Migration SQL must therefore never contain a semicolon inside a
     * string literal or comment (checked by MigrationsTest).
     */
    val statements: List<String>
        get() = sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }

    fun apply(database: SupportSQLiteDatabase) {
        statements.forEach(database::execSQL)
    }
}

/**
 * Schema history — the same SQL as iOS (`ios/MediCabinet/Sources/Persistence/Migrations.swift`), checked
 * character for character by MigrationsTest. Migrations are named, ordered and append-only: never edit one
 * that has shipped — add a new one, on both platforms.
 */
object Migrations {
    val v1 = Migration(
        name = "v1",
        sql = """
            CREATE TABLE location (
              id        TEXT PRIMARY KEY NOT NULL,
              name      TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
              isDefault INTEGER NOT NULL DEFAULT 0,
              sortOrder INTEGER NOT NULL DEFAULT 0,
              createdAt DATETIME NOT NULL,
              updatedAt DATETIME NOT NULL
            );
            CREATE UNIQUE INDEX location_single_default ON location(isDefault) WHERE isDefault = 1;

            CREATE TABLE box (
              id                 TEXT PRIMARY KEY NOT NULL,
              gtin               TEXT CHECK (gtin IS NULL OR length(gtin) = 14),
              productName        TEXT NOT NULL CHECK (length(productName) BETWEEN 1 AND 200),
              category           TEXT NOT NULL DEFAULT 'uncategorized',
              categoryOverridden INTEGER NOT NULL DEFAULT 0,
              locationId         TEXT NOT NULL REFERENCES location(id) ON DELETE RESTRICT,
              expiryYear         INTEGER,
              expiryMonth        INTEGER CHECK (expiryMonth IS NULL OR expiryMonth BETWEEN 1 AND 12),
              expiryDay          INTEGER CHECK (expiryDay IS NULL OR expiryDay BETWEEN 1 AND 31),
              expirySortKey      TEXT,
              lot                TEXT CHECK (lot IS NULL OR length(lot) <= 20),
              serial             TEXT CHECK (serial IS NULL OR length(serial) <= 20),
              isOpened           INTEGER NOT NULL DEFAULT 0,
              removalReason      TEXT CHECK (removalReason IS NULL OR removalReason IN ('usedUp', 'thrownAway')),
              removedAt          DATETIME,
              createdAt          DATETIME NOT NULL,
              updatedAt          DATETIME NOT NULL,
              CHECK ((expiryYear IS NULL) = (expiryMonth IS NULL)),
              CHECK ((expiryYear IS NULL) = (expirySortKey IS NULL)),
              CHECK (expiryDay IS NULL OR expiryYear IS NOT NULL),
              CHECK ((removalReason IS NULL) = (removedAt IS NULL))
            );
            CREATE INDEX box_gtin     ON box(gtin);
            CREATE INDEX box_location ON box(locationId);
            CREATE INDEX box_expiry   ON box(expirySortKey);
            CREATE INDEX box_active   ON box(removedAt);
            CREATE INDEX box_serial   ON box(gtin, serial) WHERE serial IS NOT NULL;
        """.trimIndent()
    )

    /**
     * `deletedAt` is the tombstone of a box added by mistake; the serial is unique among boxes not deleted.
     * The text of `fixtures/schema-v2.sql`, checked by SchemaTest.
     */
    val v2 = Migration(
        name = "v2",
        sql = """
            ALTER TABLE box ADD COLUMN deletedAt DATETIME;
            DROP INDEX box_serial;
            CREATE UNIQUE INDEX box_serial ON box(gtin, serial) WHERE serial IS NOT NULL AND deletedAt IS NULL;
        """.trimIndent()
    )

    /**
     * `deletedAt` is the tombstone of a location the user deleted; removed and deleted boxes keep pointing at it.
     * The text of `fixtures/schema-v3.sql`, checked by SchemaTest.
     */
    val v3 = Migration(
        name = "v3",
        sql = """
            ALTER TABLE location ADD COLUMN deletedAt DATETIME;
        """.trimIndent()
    )

    val all: List<Migration> = listOf(v1, v2, v3)
}
