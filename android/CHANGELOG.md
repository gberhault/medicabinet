# Changelog — Android

Newest entry first. The top entry must match `versionName` in `app/build.gradle.kts`
(checked by `scripts/check-version.sh android`).

## 0.5.0 — 2026-10-20

A Cabinet you can take in at a glance.

- The Cabinet is now one card per category (A to Z, with *Other* and *Uncategorized* last), each with its icon and its number of boxes. Inside a card, each medicine is a pill with its short name and its number of boxes: the name stops at the pharmaceutical form ("Doliprane 1000 mg" for "Doliprane 1000 mg, comprimé"), and TalkBack still reads the full name.
- The list shows no dates and no "OK". A medicine that is expired, expiring soon or of unknown expiry is marked with an icon and a tint; a plain pill has nothing to report.
- Search and filters take one line: the search field with a filter button on its right. The button opens a menu for the location and the expiry status; each active filter shows as a chip you can remove, and the button says how many are on.

## 0.4.1 — 2026-10-20

New app icon.

- MediCabinet has its own icon: a white M with a capsule on a teal background, instead of the system default.
- The icon is an adaptive icon, so it follows your launcher's shape, and it supports themed icons on Android 13 and later.

## 0.4.0 — 2026-10-20

Expiry tracking and a monthly reminder.

- Expiring tab: the boxes that are expired, expiring soon or of unknown expiry, in three sections, with the location, the date (month and year when only the month is known) and the status as text and icon. Swipe a row (or use the accessibility action) to throw a box away, with Undo. The tab badge counts expired and expiring-soon boxes on every tab.
- Settings → Expiring soon: choose 30, 60 or 90 days (60 by default); the Cabinet list, box detail, Expiring tab and reminder all follow it.
- Monthly reminder, off until you turn it on: one notification on the 1st at 09:00 with only the number of expired and expiring-soon boxes (no medicine names), sent only when there is something to report. The notification permission is asked only when you turn it on.
- Reminders are re-armed after a reboot, an app update and a time-zone or clock change; the notification arrives within the hour after 09:00 (later under battery savers).
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
