-- Schema v3 — the migration step both platforms run after v2 (whitespace and -- comments are ignored when comparing).
-- iOS: ios/MediCabinet/Sources/Persistence/Migrations.swift   Android: android/app/.../persistence/Migrations.kt
-- Never edit once shipped: add a v4 on both platforms instead (and update fixtures/schema-*.json).
--
-- deletedAt is the tombstone of a location the user deleted: kept for the future sync and export, invisible in the
-- app. Removed and deleted boxes keep pointing at their location, so the row must stay.

ALTER TABLE location ADD COLUMN deletedAt DATETIME;
