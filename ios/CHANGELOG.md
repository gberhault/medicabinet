# Changelog

Newest entry first. The top entry must match `MARKETING_VERSION` in `project.yml`
(checked by `scripts/check-version.sh`).

## 0.2.0 — 2026-10-19

Bulk scan intake.

- Boxes are named and categorised from the bundled medicines database (BDPM); only products it does not know ask for a name.
- Scan tab: point the camera at box after box; each GS1 DataMatrix adds a box with its product code, expiry, lot, serial and the session location, with a haptic, an optional sound and an Undo.
- A missing name or expiry (or an unreadable date in the code) is asked while the box is in hand; "Date not printed" saves an unknown expiry, which is shown as such, never as OK.
- A serial number already in the cabinet is refused; a box used up or thrown away can be put back; the same product without a serial asks "Another box?".
- Session list with inline correction and delete/undo, summary after Done, and manual entry from every state (no code, no camera, camera denied).
- Camera permission requested only from the Scan tab; camera stopped whenever the tab or the app is left.
- Database schema v2 (tombstone for boxes added by mistake, unique serial); existing cabinets are backed up and migrated.

## 0.1.1 — 2026-10-19

The medicine reference data, bundled; nothing scans yet.

- The French public medicines database (BDPM, 20 896 boxes, data of 2026-10-18) is bundled, so a box can be named and
  filed into one of twelve household categories offline once scanning arrives. Products BDPM has dropped stay known.
- Settings → About cites the data source, shows the date of the data and states that MediCabinet is not affiliated
  with or endorsed by ANSM, HAS or the national health insurance.
- The category names are available in French and English.

## 0.1.0 — 2026-10-19

First build: project foundation, no end-user feature yet.

- iOS app shell with four tabs (Scan, Cabinet, Expiring, Settings), French and English.
- Local cabinet database with versioned migrations and an automatic copy before every update.
- Settings → About with version and the "no medical advice" notice.
