-- Schema v2 — the migration step both platforms run after v1 (whitespace and -- comments are ignored when comparing).
-- iOS: ios/MediCabinet/Sources/Persistence/Migrations.swift   Android: android/app/.../persistence/Migrations.kt
-- Never edit once shipped: add a v3 on both platforms instead (and update fixtures/schema-*.json).
--
-- deletedAt is the tombstone of a box added by mistake (never used up or thrown away): kept for the future sync
-- and export, invisible in the app. The serial uniqueness now only concerns boxes that are not deleted.

ALTER TABLE box ADD COLUMN deletedAt DATETIME;
DROP INDEX box_serial;
CREATE UNIQUE INDEX box_serial ON box(gtin, serial) WHERE serial IS NOT NULL AND deletedAt IS NULL;
