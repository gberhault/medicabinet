# Changelog

Newest entry first. The top entry must match `MARKETING_VERSION` in `project.yml`
(checked by `scripts/check-version.sh`).

## 0.4.0 — 2026-10-20

Expiry tracking and a monthly reminder.

- Expiring tab: the boxes that are expired, expiring soon or of unknown expiry, in three sections, with the location, the date (month and year when only the month is known) and the status as text and icon. Swipe a row (or use the accessibility action) to throw a box away, with Undo. The tab badge counts expired and expiring-soon boxes on every tab.
- Settings → Expiring soon: choose 30, 60 or 90 days (60 by default); the Cabinet list, box detail, Expiring tab and reminder all follow it.
- Monthly reminder, off until you turn it on: one notification on the 1st at 09:00 with only the number of expired and expiring-soon boxes (no medicine names), sent only when there is something to report. The notification permission is asked only when you turn it on.
- The next 12 reminders are scheduled ahead and refreshed whenever the cabinet or the setting changes and when the app opens.
- The day, time zone and clock changes are followed, so statuses and the reminder stay right after midnight or a trip.

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
