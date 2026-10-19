-- Schema v1 — the exact SQL both platforms run on first launch (whitespace is ignored when comparing).
-- iOS: ios/MediCabinet/Sources/Persistence/Migrations.swift   Android: android/app/.../persistence/Migrations.kt
-- Never edit once shipped: add a v2 on both platforms instead (and update fixtures/schema-*.json).

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
