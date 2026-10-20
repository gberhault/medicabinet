# Bulk scan intake

The Scan tab turns the phone camera into a continuous intake scanner: point it at one box after another and each
box is added to the cabinet in about two seconds — no tap between boxes, no typing when the code says it all.
Spec: [`specs/bulk-scan/`](../specs/bulk-scan/). Both apps behave the same; the rules are written once as shared
fixtures (see below).

## What the user sees

1. **First visit.** A screen explains why the camera is needed (no image is saved or sent) and offers **Allow
   camera** and **Add without scanning**. The system prompt only appears after the button.
2. **Scanning.** The live camera, the session location (preselected: the last one used, else the default; change
   it in one tap), a sound toggle (off by default), the number of boxes added and **Done**.
3. **Each box.** The moment a code is read the box is saved, with a haptic (and a short tone if sound is on), a
   card with the name and the expiry (or *Expiry unknown* with a warning icon), and **Undo**.
4. **What the code cannot tell.** A product the app does not know, or a code with no (or an unreadable) expiry,
   opens a short form *while the box is still in hand*: a name, and a month/year (+ optional day) — or
   **Date not printed**, which saves the box with an unknown expiry. Nothing is preselected and an expiry is
   never guessed. Cancel adds nothing.
5. **Duplicates.** A serial number already in the cabinet is never added again ("Already in the cabinet:
   Bathroom"). A box that was used up or thrown away offers **Put back in the cabinet**. The same product with no
   serial, scanned again after the first box left the frame, asks "Another box of X?" without stopping the
   camera.
6. **The session list** shows what was added, newest first; tap a row to correct the name, the expiry (including
   back to unknown) or the location; delete a row with Undo. **Done** shows a summary (boxes added, boxes without
   an expiry date). Every box was already saved, so leaving without Done loses nothing.
7. **No camera?** Permission denied, restricted, no camera, camera failing to start: the screen says so and
   **Add without scanning** always works (name, optional barcode, expiry, location).

A product is named, and categorised, from the bundled medicines database ([`reference-data.md`](reference-data.md)),
else from the last box with the same code (keeping a category the user set), else the user types the name. Boxes
whose product is not in the database stay *uncategorized*.

## The rules (shared fixtures)

| Rule | Fixture | Core type (Swift / Kotlin) |
|------|---------|----------------------------|
| GS1 DataMatrix and EAN-13 parsing | `fixtures/gs1-datamatrix.json` | `GS1Parser` |
| One box, one scan (1 s re-scan gap) | `fixtures/scan-debounce.json` | `ScanDebouncer` |
| Add / complete / confirm another / refuse | `fixtures/scan-intake.json` | `IntakePolicy` |
| Product name and category | `fixtures/product-resolution.json` | `ProductResolver` |

Parsing facts worth knowing: the expiry is AI `17` (`YYMMDD`, year `2000 + YY`); day `00` means *end of that
month*; an impossible date is reported **invalid** (never clamped) and a missing one **missing**; AI `15`/`16` are
not expiry dates; a lot or serial over 20 characters or outside the GS1 character set is dropped (the GTIN and the
expiry are kept); a symbology identifier (`]d2`) and leading or trailing group separators are accepted; **if the
group separator is missing, a variable-length field swallows what follows and the expiry is reported missing —
it is never searched for inside another field** (the user is asked instead). Malformed input never crashes: the
parser returns a typed error (`empty`, `tooLong`, `invalidCharacters`, `missingGTIN`, `invalidGTIN`).

The product lookup is the `ProductLookup` port owned by the reference-data epic; the app binds
`AppEnvironment.reference` to it. `ScanIntake` (both cores) turns a scan into an outcome and adds, puts back, deletes and restores boxes; a failing
reference lookup leaves the product unknown instead of stopping intake.

## Data

Schema v2 (`fixtures/schema-v2.sql`): `box.deletedAt` (tombstone of a box added by mistake) and a unique index on
(GTIN, serial) for boxes that are not deleted. Existing cabinets are backed up and migrated on launch. The export
`formatVersion` is unchanged (export arrives with epic #26, which will carry the tombstones for sync).

## Privacy and permissions

- iOS: `NSCameraUsageDescription` (French and English) — requested only from the Scan tab's own button; two UI
  preferences in `UserDefaults` (last location, sound), declared in `PrivacyInfo.xcprivacy`.
- Android: the `CAMERA` runtime permission (hardware not required, so the app installs on camera-less devices) and
  two preferences in `SharedPreferences`. The merged manifest has no other permission (`ACCESS_NETWORK_STATE`
  pulled in by CameraX's `media3-common` is removed) and no `INTERNET`.
- No payload, lot, serial or product name is ever logged; frames are never stored. The debug payload-capture view
  (Settings → Debug, debug builds only) shows the *shape* of a payload — never a lot or serial value.
- Android decoder: zxing-cpp (Apache-2.0, offline). ML Kit was rejected: it sends usage metrics to Google.

## Testing without a camera

- Unit: the view model runs against a real in-memory SQLite cabinet and a scanner the test pushes frames into.
- iOS UI tests launch with `-UITestCameraAccess <notDetermined|authorized|denied|restricted|unsupported>` and
  replay payloads from `MEDICABINET_UI_TEST_SCANS` (`|`-separated, `<GS>` for a group separator), optionally with
  `MEDICABINET_UI_TEST_PRODUCTS` (`gtin=Name|…`) and `MEDICABINET_UI_TEST_SCAN_INTERVAL_MS`.
- Android Compose tests use a `FakeCodeScanner` and Robolectric's permission shadow.

## Not yet

- Real-box payload shapes (task T6) and the on-device timing (T28) need a phone: see `reports/bulk-scan/`.
  One Android shape is captured (`]d2 01 17 10(5)`); boxes with a serial and an iPhone are still to do.
