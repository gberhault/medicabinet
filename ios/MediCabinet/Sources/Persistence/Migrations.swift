import GRDB

/// Schema history. Migrations are named, ordered and append-only: never edit one that has
/// shipped — add a new one. `eraseDatabaseOnSchemaChange` is deliberately never used.
enum Migrations {
    static let v1 = "v1"
    static let schemaV2 = "v2"
    static let schemaV3 = "v3"
    static let schemaV4 = "v4"
    /// Every migration, in the order they run.
    static let identifiers = [v1, schemaV2, schemaV3, schemaV4]

    /// The v1 schema SQL; `fixtures/schema-v1.sql` holds the same text and a test compares them.
    static let v1SQL = """
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
        """

    /// The v2 migration step; `fixtures/schema-v2.sql` holds the same text and a test compares them.
    /// `deletedAt` is the tombstone of a box added by mistake; the serial is unique among boxes not deleted.
    static let schemaV2SQL = """
        ALTER TABLE box ADD COLUMN deletedAt DATETIME;
        DROP INDEX box_serial;
        CREATE UNIQUE INDEX box_serial ON box(gtin, serial) WHERE serial IS NOT NULL AND deletedAt IS NULL;
        """

    /// The v3 migration step; `fixtures/schema-v3.sql` holds the same text and a test compares them.
    /// `deletedAt` is the tombstone of a location the user deleted; removed and deleted boxes keep pointing at it.
    static let schemaV3SQL = """
        ALTER TABLE location ADD COLUMN deletedAt DATETIME;
        """

    /// The v4 migration step; `fixtures/schema-v4.sql` holds the same text and a test compares them. `namedProduct`
    /// is the name and category the household gave a code the reference database does not name (one live row per
    /// GTIN, a forgotten one keeps its tombstone); `cabinetMeta` marks the one-time fill of it from the boxes.
    static let schemaV4SQL = """
        CREATE TABLE namedProduct (
          id                 TEXT PRIMARY KEY NOT NULL,
          gtin               TEXT NOT NULL CHECK (length(gtin) = 14),
          name               TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
          category           TEXT NOT NULL DEFAULT 'uncategorized',
          categoryOverridden INTEGER NOT NULL DEFAULT 0,
          createdAt          DATETIME NOT NULL,
          updatedAt          DATETIME NOT NULL,
          deletedAt          DATETIME
        );
        CREATE UNIQUE INDEX namedProduct_gtin ON namedProduct(gtin) WHERE deletedAt IS NULL;

        CREATE TABLE cabinetMeta (
          key   TEXT PRIMARY KEY NOT NULL,
          value TEXT NOT NULL
        ) WITHOUT ROWID;
        INSERT INTO cabinetMeta (key, value) VALUES ('namedProductBackfill', 'pending');
        """

    static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()
        migrator.registerMigration(v1) { db in
            try db.execute(sql: v1SQL)
        }
        migrator.registerMigration(schemaV2) { db in
            try db.execute(sql: schemaV2SQL)
        }
        migrator.registerMigration(schemaV3) { db in
            try db.execute(sql: schemaV3SQL)
        }
        migrator.registerMigration(schemaV4) { db in
            try db.execute(sql: schemaV4SQL)
        }
        return migrator
    }
}
