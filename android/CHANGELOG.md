# Changelog — Android

Newest entry first. The top entry must match `versionName` in `app/build.gradle.kts`
(checked by `scripts/check-version.sh android`).

## 0.3.0 — 2026-10-20

Cabinet inventory, locations and removal by scan.

- Cabinet tab: everything in the cabinet as one row per product, grouped by category, with the box count, the earliest expiry and a status (expired, expiring soon, expiry unknown, OK — as text and icon; never OK while a box has no known date). Search by name (accents and case ignored) and filter by location and expiry status; the list updates by itself.
- Box detail: edit the name, expiry (never guessed) and location, mark a box opened, change its category (or hand it back to the automatic one), and take it out as used up, thrown away or added by mistake — each with Undo.
- Remove mode in the scanner: an Add / Remove switch (always opens in Add), a used-up / thrown-away reason remembered between sessions, a picker when several boxes match, Undo per box and a summary.
- Locations: manage them from Settings (add, rename, reorder, default, delete with the boxes moved elsewhere).
- Database schema v3 (location tombstone); existing cabinets are backed up and migrated.

## 0.2.0 — 2026-10-19

Bulk scan intake.

- Boxes are named and categorised from the bundled medicines database (BDPM); only products it does not know ask for a name.
- Scan screen: point the camera at box after box; each GS1 DataMatrix adds a box with its product code, expiry, lot, serial and the session location, with a haptic, an optional sound and an Undo. Decoding is offline (zxing-cpp on CameraX).
- A missing name or expiry (or an unreadable date in the code) is asked while the box is in hand; "Date not printed" saves an unknown expiry, which is shown as such, never as OK.
- A serial number already in the cabinet is refused; a box used up or thrown away can be put back; the same product without a serial asks "Another box?".
- Session list with inline correction and delete/undo, summary after Done, and manual entry from every state (no code, no camera, camera denied).
- The only permission is the camera, requested from the Scan screen's own button; the app installs and works (manual entry) on devices without one.
- Database schema v2 (tombstone for boxes added by mistake, unique serial); existing cabinets are backed up and migrated.

## 0.1.1 — 2026-10-19

The medicine reference data, bundled; nothing scans yet.

- The French public medicines database (BDPM, 20 896 boxes, data of 2026-10-18) is bundled, so a box can be named and
  filed into one of twelve household categories offline once scanning arrives. Products BDPM has dropped stay known.
  The database is copied to internal storage on first use (1.7 MB, excluded from backups) and checked against its
  manifest.
- Settings → About cites the data source, shows the date of the data and states that MediCabinet is not affiliated
  with or endorsed by ANSM, HAS or the national health insurance.
- The category names are available in French and English.

## 0.1.0 — 2026-10-19

First build: project foundation, no end-user feature yet.

- Android app shell with four destinations (Scan, Cabinet, Expiring, Settings), French and English.
- Local cabinet database (the same SQLite schema as iOS) with versioned migrations and an automatic copy before every update.
- Settings → About with version and the "no medical advice" notice.
- No permissions; the cabinet and its backup copies are included in Android Auto Backup.
