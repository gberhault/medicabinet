# Bulk scan intake — Requirements

Status: **Approved 2026-10-19**
Epic: #12 · Children: #13, #14, #15, #16, #40 · Branch: `feature/12-bulk-scan` · Platforms: iOS + Android

## Introduction

Stocking a home medicine cabinet by hand takes an evening; nobody does it, so nobody knows
what they have or whether it is still safe. French medicine boxes carry a GS1 DataMatrix that
holds the product code (GTIN/CIP13), usually the expiry, the lot and a serial number. This
epic turns the phone camera into a continuous intake scanner: the household empties the
drawer, points the phone at one box after another, and each box lands in the cabinet in about
two seconds with its product, expiry and location. The app asks — while the box is still in
hand — only for what the box cannot tell it, never guesses an expiry date, and never counts
the same box twice.

> We are building continuous scan intake on iOS and Android, so that a household can take
> stock of its whole cabinet in minutes. Each box gets its GTIN, expiry, lot, serial and the
> session's location from the DataMatrix, and the app asks for what is missing while the box
> is in hand. We are **not** building the BDPM lookup or classification (#7), locations
> management (#18), removal by scan (#21), expiry status labels (#23), or reading printed
> dates with the camera.

### Decisions taken in the challenge (2026-10-19)

1. **Product names depend on epic #7.** This epic is built against a product-lookup port.
   The epic PR is opened only once the BDPM lookup (#11) is on `develop` and bound, so the
   released scan does not ask a name for every box.
2. **Ask while the box is in hand.** A missing/unreadable expiry or an unknown product opens
   a short completion step right after the scan, not at the end of the session.
3. **Schema v2** adds a deletion tombstone to boxes and a database-level unique serial (#40).
4. **Same product without serial**: a non-blocking "another box?" prompt; nothing is added
   unless the user confirms.
5. **Android decoder**: an open-source, offline decoder on CameraX (no ML Kit — it sends usage
   metrics to Google); final choice and verification in the design.
6. **Real payload shapes** are captured once on the user's own boxes through a debug-only
   view that never shows lot or serial values; anonymised payloads go into the shared fixture.
7. **Locations**: the scanner picks among existing locations; it does not create them (#18).
8. **Sound**: optional, off by default. **Version**: 0.2.0 on both apps.

## Glossary

- **Payload** — the text a scanner returns for one code.
- **GS** — the group separator (ASCII 29) that ends a variable-length GS1 field; the code's
  `FNC1` character. Scanners may drop it, keep it, or prefix the payload with a symbology
  identifier (`]d2` for GS1 DataMatrix).
- **AI** — GS1 Application Identifier: `01` GTIN (14 digits), `17` expiry (`YYMMDD`), `10`
  lot (≤ 20 chars), `21` serial (≤ 20 chars).
- **Scan session** — from opening the Scan tab until the user taps **Done** (or the app process
  ends). Each box is saved the moment it is added; the session only drives the review list.
- **Session location** — the location every box added in the session goes to.
- **Completion step** — the short form shown right after a scan when the product name or the
  expiry is unknown.
- **Deleted box** — a box removed because it was added by mistake (not used up, not thrown
  away). Kept as a tombstone (`deletedAt`) for future sync, invisible everywhere in the app.
- **Shared fixture** — a JSON test-vector file under `fixtures/` run by both platforms.

## User stories and acceptance criteria

Requirement IDs: **P** parser, **D** scan debouncing, **C** scanner screen, **I** intake
decisions and duplicates, **K** completion step, **R** session review list, **M** manual
entry, **L** product lookup, **V** schema v2, **X** cross-cutting.

### P — GS1 DataMatrix and EAN-13 parsing (#13)

*As a household member, I want the app to read everything the box's code holds, so that I do
not type it — and to never invent a date the code does not really hold.*

1. The parser SHALL live in both cores (`MediCabinetCore`, Kotlin `:core`), with no platform
   framework, and SHALL pass every case of `fixtures/gs1-datamatrix.json` on both platforms.
2. WHEN a payload is a GS1 element string, the parser SHALL extract AI `01` (GTIN-14), `17`
   (expiry), `10` (lot) and `21` (serial), in any order.
3. The parser SHALL accept a payload with or without a leading symbology identifier (`]d2`,
   `]Q3`, `]C1`) and with or without a leading `FNC1`/GS, and SHALL treat GS as the end of a
   variable-length field. A GS after a fixed-length field SHALL be tolerated.
4. WHEN the payload is 13 digits (EAN-13, e.g. a CIP13 `3400…` or ACL `3401…` 1D barcode),
   the parser SHALL return the GTIN-14 `0` + those digits, with no expiry, lot or serial.
5. WHEN a GTIN's GS1 check digit is wrong or its length is not 14 (13 for EAN-13), the parser
   SHALL reject the payload with error `invalidGTIN`.
6. WHEN AI `17` is `YYMMDD` with `DD = 00`, the expiry SHALL be month-precise (last day of that
   month); otherwise day-precise. The year SHALL be `2000 + YY`.
7. WHEN AI `17` is present but its month or day is invalid, the expiry SHALL be reported as
   **invalid** (not missing), with the `ExpiryDate` error kind (`invalidFormat` when AI `17` is not six digits); it SHALL never be clamped,
   corrected or replaced by a nearby date.
8. WHEN AI `17` is absent — including when it may be hidden inside a variable-length field
   because a GS is missing — the expiry SHALL be reported as **missing**. The parser SHALL
   never search for an expiry with heuristics inside another field's value.
9. AIs `15` (best before) and `16` (sell by) SHALL NOT be used as the expiry.
10. WHEN a lot or serial is longer than 20 characters or contains characters outside the GS1
    character set 82, that field SHALL be dropped (reported absent); the GTIN and expiry SHALL
    still be returned.
11. WHEN the parser meets an AI it does not know, it SHALL stop reading; fields already read
    SHALL be returned, fields after it SHALL be absent.
12. WHEN the payload is empty, longer than 512 characters, contains control characters other
    than GS, or holds no AI `01`, the parser SHALL reject it with a typed error
    (`empty`, `tooLong`, `invalidCharacters`, `missingGTIN`). Error kinds SHALL be identical on
    both platforms and listed in `fixtures/README.md`.
13. The parser SHALL never throw an unexpected exception or crash, whatever the input
    (fuzz-style test with random and truncated payloads on both platforms).
14. A core function SHALL describe a payload's **shape** (prefix, AIs in order with lengths,
    GS positions) without any lot or serial value, for the debug capture view (C10).

### D — One box, one scan (#14)

*As a household member, I want the box I am holding in front of the camera to count once, so
that the list matches the drawer.*

1. Scanning SHALL be continuous: no tap between boxes.
2. WHILE the same payload stays visible, the system SHALL count it once.
3. WHEN a payload becomes visible again after it has been absent for at least the re-scan gap
   (named default **1.0 s**), the system SHALL treat it as a new scan (the intake rules of I
   then decide whether a box is added).
4. WHEN several codes are visible at once, each SHALL be handled once, in the order they were
   first seen.
5. The debouncing rule SHALL live in both cores, use the injected clock, and pass every case of
   `fixtures/scan-debounce.json` on both platforms.

### C — Continuous scanner screen (#14)

*As a household member, I want to open the Scan tab and start scanning straight away with one
hand, so that stocking the cabinet takes minutes.*

1. WHEN the user opens the Scan tab and the camera permission is not decided, the system SHALL
   explain in one screen why the camera is needed and offer **Allow camera** and **Add without
   scanning**; the system permission prompt SHALL appear only after **Allow camera**.
2. WHEN the permission is granted, the Scan tab SHALL show the live camera with a visible
   scanning area, the session location, the session count and **Done**.
3. WHEN the permission is denied or restricted, or the device cannot scan, the system SHALL
   say so, offer **Open Settings** (when the permission can be changed there) and **Add
   without scanning**; manual entry SHALL always work.
4. WHILE the camera is starting, the system SHALL show a loading state; WHEN it fails to start,
   an error state with **Retry** and **Add without scanning**.
5. The session location SHALL be pre-selected (the last location used for a scan session if it
   still exists, else the default location), shown on the camera screen, and changeable in one
   tap from the existing locations. A change SHALL apply to boxes added afterwards only.
6. WHEN a box is added, the system SHALL within **300 ms** of the scan give a success haptic and
   show a confirmation with the product name, the expiry (or "expiry unknown") and **Undo**;
   WHEN sound is on, it SHALL also play a short sound. A non-added scan (duplicate, unreadable
   code) SHALL use a distinct haptic and message.
7. The camera SHALL run only while the Scan tab is visible and the app is in the foreground;
   it SHALL stop when the user switches tab, leaves the app, or locks the phone, and resume on
   return. WHILE a completion step or prompt is open, recognitions SHALL be ignored.
8. Sound SHALL be a toggle on the scanner screen, off by default, remembered across launches.
9. The scanner SHALL recognise GS1 DataMatrix and EAN-13 only. WHEN a code is recognised but
   rejected by the parser, the system SHALL show "code not recognised" once per payload and keep
   scanning.
10. In **debug builds only**, the scanner SHALL offer a capture view listing the shape (P14) of
    each payload seen; release builds SHALL not contain it.
11. The camera source SHALL sit behind a scanner interface so that UI tests inject payloads
    without a camera.

### I — Intake rules and duplicate protection (#15)

*As a household member, I want the app to refuse a box it already has and to check with me
when it cannot tell, so that nothing is counted twice and nothing is silently skipped.*

1. The intake decision SHALL live in both cores and pass every case of
   `fixtures/scan-intake.json` on both platforms.
2. WHEN a scan has a serial and a box with the same GTIN and serial is in the cabinet, the
   system SHALL NOT add a box and SHALL say it is already in the cabinet, with its location.
3. WHEN a scan has a serial and the matching box was removed (used up / thrown away), the
   system SHALL NOT add a box and SHALL offer **Put back**, which undoes the removal and moves
   the box to the session location.
4. WHEN the matching box was deleted (added by mistake), the scan SHALL be treated as new.
5. WHEN a scan has no serial and a box of the same GTIN was already added in this session (and
   not deleted), the system SHALL ask "Another box of *name*?" without stopping the camera,
   and SHALL add a box only if the user confirms. The prompt SHALL disappear at the next scan.
6. Otherwise the system SHALL add one box with the GTIN, expiry, lot, serial, the session
   location and the resolved product (L), going through the completion step (K) first when the
   name or the expiry is unknown.
7. The uniqueness of (GTIN, serial) among boxes that are not deleted SHALL also be enforced by
   the database (V2); a race SHALL end in "already in the cabinet", never in two boxes.

### K — Completion step: ask while the box is in hand (#16)

*As a household member, I want to be asked for a missing date or name right after the scan,
while I still hold the box, so that I never have to find it again later.*

1. WHEN a scan's expiry is missing or invalid, or its product is unknown, the system SHALL open
   a completion step before saving, showing what is known (name or code, lot) and asking only
   for what is missing.
2. The expiry SHALL be entered as month and year, with an optional day, and validated through
   `ExpiryDate` (years 2000–2099). WHEN the code held an invalid date, the step SHALL say the
   code's date is unreadable and SHALL NOT pre-fill a date.
3. The step SHALL offer **Date not printed**, which saves the box with an unknown expiry; such a
   box SHALL be shown as "expiry unknown" in the confirmation and in the review list, never as
   OK.
4. WHEN the product is unknown, the step SHALL ask for a name (1–200 characters, trimmed).
5. **Cancel** SHALL add nothing. Confirming SHALL add the box under the same rules as I6 and
   resume scanning.

### R — Session review list (#15)

*As a household member, I want to see what I just added and fix mistakes on the spot, so that
the cabinet is right before I put the boxes away.*

1. The scanner SHALL show the number of boxes added in the session and open the session list,
   newest first: name, expiry (or "expiry unknown" with an icon), location.
2. WHEN the user edits a row, the system SHALL allow changing the name, the expiry (including
   back to unknown, never to a guessed value) and the location, and save immediately.
3. WHEN the user deletes a row (or taps **Undo** on a confirmation), the box SHALL be deleted
   (tombstone) and the system SHALL offer **Undo**, which restores it.
4. WHEN the user taps **Done**, the system SHALL show a summary (boxes added, boxes with an
   unknown expiry) and end the session. Boxes are already saved; leaving without **Done**
   SHALL lose nothing.

### M — Manual entry (#16)

*As a household member, I want to add a box without scanning it — no code, damaged code, no
camera — so that every box can be recorded.*

1. **Add without scanning** SHALL be reachable from the scanner, the permission screen and the
   camera-error state.
2. The form SHALL ask for a name (required unless a typed code resolves it), an optional code
   (EAN-13 / CIP13 / GTIN-14 digits, check digit validated), the expiry (month/year/optional
   day, or **Date not printed**) and the location (session location pre-selected).
3. The added box SHALL follow the intake rules (I) and appear in the session list.
4. Manual entry SHALL work with the camera permission denied and on a device with no camera.

### L — Product resolution (#12, binding with #11)

*As a household member, I want each box named automatically when the app knows the product,
so that I only type names for products it has never seen.*

1. Product resolution SHALL go through a product-lookup port; until #11 is bound, the bound
   implementation SHALL answer "unknown" for every GTIN.
2. WHEN the reference lookup knows the GTIN, the box SHALL take its name.
3. WHEN the reference lookup does not know the GTIN but a box with that GTIN exists in the
   cabinet (in the cabinet or removed, not deleted), the new box SHALL take the most recent such
   box's name, and its category when that category was set by the user.
4. Otherwise the product SHALL be unknown (K4). New boxes SHALL be `uncategorized` unless L3
   applies (classification arrives with #10).
5. The epic PR SHALL be opened only when L1 is bound to the BDPM lookup from #11.

### V — Schema v2: box tombstone and unique serial (#40)

*As a household member, I want a mistaken scan to be removable without breaking future sync,
and the database itself to refuse a duplicate box.*

1. A v2 migration, identical on both platforms and checked by `fixtures/schema-v2.json`, SHALL
   add `box.deletedAt` (nullable date) and a unique index on (GTIN, serial) for boxes with a
   serial that are not deleted. v1 SHALL stay unchanged.
2. WHEN an existing v1 cabinet is opened, the system SHALL back it up and migrate it with the
   existing bootstrap rules (backup first; failure restores the copy and shows the error).
3. Every existing repository query SHALL ignore deleted boxes; new repository operations SHALL
   delete (stamp `deletedAt` and `updatedAt`), undo a delete, find a box by (GTIN, serial), and
   find the most recent box with a GTIN — each covered by a shared repository-contract check.

### X — Cross-cutting

1. **Privacy**: payloads, lots, serials and product names SHALL never be logged; camera frames
   SHALL never be stored. The only new permission SHALL be the camera (iOS usage description
   in French and English; Android `CAMERA` runtime permission, camera hardware not required).
   No new network access, analytics or Google ML Kit.
2. **Performance**: scan → confirmation < 300 ms with a 1 000-box cabinet; ≥ 1 box / 2 s
   sustained; no database or decoding work on the main thread; camera stopped when not visible.
3. **Accessibility**: every control labelled for VoiceOver/TalkBack; each added box announced;
   44 pt / 48 dp targets; Dynamic Type and 200 % font scale; statuses carry text or icon, not
   colour alone; WCAG AA contrast over the camera preview.
4. **i18n**: every new string in French and English on both platforms, same wording; dates
   formatted with the user's locale.
5. **Docs**: `docs/bulk-scan.md` (behaviour, rules, privacy), `docs/architecture.md` (schema
   v2, scanner layering), `fixtures/README.md` (new fixtures and names).
6. **Version**: iOS 0.2.0 (build 2) and Android 0.2.0 (versionCode 2), with changelog entries.
7. Each feature SHALL have success, failure and edge-case tests on both platforms; core
   coverage targets 100 % of branches, new app code 90 %.

## Out of scope

- BDPM reference data, product lookup implementation and classification (epic #7: #8–#11);
  this epic only defines and binds the port.
- Creating, renaming or deleting locations (#18); inventory list and box detail (#19, #20).
- Removing a box by scanning it (#21); expiry status labels and the "expiring soon" setting
  (#23); notifications (#25).
- Reading the printed expiry date with text recognition (possible later epic, would need its
  own reliability rules).
- Unit counts inside a box; scanning several phones into one cabinet; export/import.

## Open points to confirm during implementation

1. **Payload shapes** (C10, P3): the capture view must be run on 10–20 real boxes on an iPhone
   and an Android phone before the fixture is frozen; if a scanner drops GS separators, the
   design's GS handling is revisited (spec updated first).
