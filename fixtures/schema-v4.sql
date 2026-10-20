-- Schema v4 — the migration step both platforms run after v3 (whitespace and -- comments are ignored when comparing).
-- iOS: ios/MediCabinet/Sources/Persistence/Migrations.swift   Android: android/app/.../persistence/Migrations.kt
-- Never edit once shipped: add a v5 on both platforms instead (and update fixtures/schema-*.json).
--
-- namedProduct is what the household taught the app about one code the bundled medicines database does not name: a
-- name and a category, one live row per GTIN (a forgotten row keeps its deletedAt tombstone). A box keeps its own
-- name: it is a copy, never a reference. cabinetMeta holds process state kept inside the database so a restored
-- backup or an import brings it back with it; the only key today is namedProductBackfill, set to pending here and
-- cleared once the app has filled namedProduct from the existing boxes (the reference data decides which codes).

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
