# Bulk scan intake — Tasks

Status: **Approved 2026-10-19**
Epic: #12 · Branch: `feature/12-bulk-scan`

One task = one focused commit (code + tests + this file checked off). Tags: `[shared]`,
`[ios]`, `[android]`. Child issue in brackets; requirement IDs refer to
[`requirements.md`](requirements.md), sections (§) to [`design.md`](design.md).

Order: shared fixture → both cores → camera adapters and the **real-box capture (T6), which
can change the parser fixture before anything is built on top of it** → remaining rules →
schema v2 → intake service → UI per platform → docs, measurements, BDPM binding, version.
The iOS UI block (T15–T20) and the Android UI block (T21–T26) are independent of each other.

## 1. Parser (#13)

- [x] **T1 — GS1 fixture** `[shared]` [#13] (P1–P12, P14; §2.1)
  `fixtures/gs1-datamatrix.json` with the seed cases of §2.1 (anonymised GTINs with valid
  check digits, invented lots/serials); parse error kinds in `fixtures/README.md`; broken
  copy under `scripts/tests/fixtures-broken/`. *Test:* `check-fixtures.py` green.
- [x] **T2 — `GS1Parser`, `GTIN`, shapes in `MediCabinetCore`** `[ios]` [#13] (P1–P14; §3.1)
  *Tests first:* `GS1ParserFixtureTests` (one per case), `GS1ParserTests` (check digit, AI
  table, duplicated AI), `GS1ParserFuzzTests` (seeded random + every prefix of every fixture
  payload, never crashes), shape never contains a value. Break script covers the new fixture.
  100 % branch coverage.
- [x] **T3 — Kotlin twin of the parser in `:core`** `[android]` [#13] (P1–P14; §3.1)
  Same tests (`@TestFactory` per case, fuzz, shape), same error kinds; break script.

## 2. Camera adapters and real payloads (#14, #13)

- [x] **T4 — iOS camera adapter and debug capture view** `[ios]` [#14] (C9–C11, X1; §1.2, §5)
  `CodeScanner` protocol, `VisionKitScanner`, `InjectedCodeScanner`; `PayloadCaptureView`
  (`#if DEBUG`, from Settings → Debug) listing `GS1Parser.shape` of each payload;
  `NSCameraUsageDescription` (fr + en); SwiftLint rule confining VisionKit/AVFoundation to
  `Features/Scan/Camera/` + lint fixture. *Tests:* injected scanner emits snapshots; Info.plist
  key present and localised; lint fixture fails.
- [x] **T5 — Android camera adapter and debug capture screen** `[android]` [#14] (C9–C11, X1; §1.1, §6)
  *Checked 2026-10-19:* zxing-cpp `android` 3.1.1 is Apache-2.0, last released 2026-07-29, its AAR declares no
  permission and 4 × ~1.2–1.9 MB native libraries; CameraX 1.6.2 `camera-view` pulls in `media3-common`, whose
  `ACCESS_NETWORK_STATE` is removed from our manifest (`tools:node="remove"`). Debug APK (all ABIs): 28.6 MB.
  Add CameraX + `io.github.zxing-cpp:android` to the catalog after checking licence, last
  release date, merged manifest and APK size delta (recorded in this file and the PR).
  `CodeScanner`, `CameraXScanner` (`DATA_MATRIX`, `EAN_13`, `PLAIN`, symbology prefix),
  `FakeCodeScanner`; `PayloadCaptureScreen` in `src/debug`; `CAMERA` permission +
  `uses-feature required=false`; `PrivacyTest` = exactly CAMERA; architecture rule confining
  `androidx.camera`/`zxingcpp` to `features/scan/camera/` + fixture. *Tests:* PrivacyTest,
  architecture fixture, fake scanner flow.
- [ ] **T6 — Capture real payload shapes** *(1 of 10–20 so far: Android/zxing-cpp gives `]d2 01 17 10(5)`, no leading FNC1 — added to the fixture; iOS and boxes with a serial still to do)* `[shared]` [#13] (open point 1; §12)
  **Needs the user:** run the debug capture on 10–20 boxes on an iPhone and an Android phone
  (`ANDROID_SERIAL` = the device the user names). Add one anonymised case per new shape to
  `gs1-datamatrix.json` (both suites must pass). If a scanner drops GS separators, stop and
  update requirements/design first (§1.1–1.2).

## 3. Scan rules (#14, #15, #12)

- [x] **T7 — Debounce, intake and resolution fixtures** `[shared]` [#14, #15] (D5, I1, L; §2.2–2.4)
  `scan-debounce.json`, `scan-intake.json`, `product-resolution.json`; decision names in
  `fixtures/README.md`; broken copies. *Test:* `check-fixtures.py` green.
- [x] **T8 — `ScanDebouncer`, `ProductResolver`, `IntakePolicy` in Swift** `[ios]` (D1–D5, I1–I6, K1, L2–L4; §3.2–3.4)
  *Tests first:* fixture tests for the three files; debouncer performance test (§8). Break
  script extended.
- [x] **T9 — Kotlin twins** `[android]` (same)
  Same tests on `:core`; break script extended.

## 4. Schema v2 (#40)

- [x] **T10 — Schema v2 fixtures and contract names** `[shared]` [#40] (V1, V3; §2.5, §4)
  `schema-v2.sql`, `schema-v2.json`, new checks in `repository-contract.json`,
  `duplicateSerial` in `fixtures/README.md`. *Test:* `check-fixtures.py` green.
- [x] **T11 — iOS v2 migration and repository additions** `[ios]` [#40] (V1–V3, I7; §4)
  `Migrations.v2` (text equals the fixture), GRDB `box(gtin:serial:)`, `latestBox(gtin:)`,
  `delete`, `undoDelete`, deleted boxes hidden, unique violation → `.duplicateSerial`; core
  ports + in-memory fake + `RepositoryContract` checks. *Tests:* contract on fake and GRDB,
  `SchemaFixtureTests` v2 (and v1 at version 1), upgrade v1 → v2 with data, bootstrap backup
  before v2.
- [x] **T12 — Android v2 migration and repository additions** `[android]` [#40] (same)
  `Migrations.v2`, `SqlBoxRepository`, Kotlin ports + fake + contract. Same tests (Robolectric).

*T11 and T12 landed in one commit: the shared `repository-contract.json` lists the new checks, so shipping one platform alone would leave the other's suite red.*

## 5. Intake service (#15, #16, #12)

- [x] **T13 — `ProductLookup`, `ScanIntake` in Swift** `[ios]` (I2–I7, K, M2, L1–L4, R3; §3.3–3.4)
  `UnknownProductLookup` in the app. *Tests:* `ScanIntakeTests` on the in-memory fake (every
  outcome, put back, delete / undo delete, duplicate race → `.duplicateSerial`, name
  validation, manual code check digit); iOS app perf test evaluate + add on a 1 000-box GRDB
  cabinet ≤ 100 ms median (§8).
- [x] **T14 — Kotlin twin** `[android]` (same)
  Same tests; Robolectric SQLite perf test.

## 6. iOS scan UI (#14, #15, #16)

- [x] **T15 — Scan tab skeleton and permission states** `[ios]` [#14] (C1–C5, C7, C8, M1; §5)
  `ScanPreferences` (UserDefaults + `PrivacyInfo` CA92.1), `ScanViewModel` states,
  `CameraPermissionView`, `ScanView` replacing the placeholder, camera start/stop on
  appear/disappear/scene phase, location chip, sound toggle. *Tests:* view-model per state,
  preferences round-trip, privacy manifest test, UI test of the permission screen.
- [x] **T16 — Scanning loop and feedback** `[ios]` [#14, #15] (C6, C9, D, I2–I6; §5, §7.1–7.5)
  Debouncer → parse → `evaluate` → outcomes; confirmation card with **Undo**, already-in-cabinet,
  put back, another box, not recognised; haptics, `scan_confirm.caf`, VoiceOver announcement.
  *Tests:* view-model per outcome with injected payloads; UI test adding two boxes and a
  duplicate.
- [x] **T17 — Expiry field and completion sheet** `[ios]` [#16] (K1–K5; §5)
  *Tests:* `ExpiryFieldTests` (month/year/day, bounds, leap day, no prefill on invalid),
  UI test: EAN-13 → completion → **Date not printed** → "expiry unknown" row.
- [x] **T18 — Session list, edit, delete/undo, summary** `[ios]` [#15] (R1–R4; §7.6)
  *Tests:* view-model edit/delete/undo/summary; UI test.
- [x] **T19 — Manual entry** `[ios]` [#16] (M1–M4; §7.7)
  *Tests:* view-model (code check digit, name rules); UI test with camera denied.
- [x] **T20 — iOS accessibility pass** `[ios]` (X3)
  *Tests:* UI tests at an accessibility text size and in dark mode; every control labelled.

*T15–T19 landed together in the iOS Scan-tab commit (one view model, one set of screens), T21–T25 in the Android one; the view-model and screen tests carry the per-task coverage.*

## 7. Android scan UI (#14, #15, #16)

- [x] **T21 — Scan route skeleton and permission states** `[android]` (as T15; §6)
  `ScanPreferences` (SharedPreferences), `ScanViewModel`, permission content, CameraX lifecycle
  binding. *Tests:* view-model per state, Robolectric screen tests.
- [x] **T22 — Scanning loop and feedback** `[android]` (as T16)
  Haptics, `scan_confirm.ogg` via SoundPool, live region. *Tests:* view-model per outcome
  (Turbine), Robolectric UI.
- [x] **T23 — Expiry field and completion sheet** `[android]` (as T17)
- [x] **T24 — Session list, edit, delete/undo, summary** `[android]` (as T18)
- [x] **T25 — Manual entry** `[android]` (as T19)
- [x] **T26 — Android accessibility pass** `[android]` (X3)
  Font scale 2.0, dark theme, TalkBack labels, 48 dp targets.

## 8. Finish

- [x] **T27 — Docs** `[shared]` (X5)
  `docs/bulk-scan.md` (+ `docs/README.md` link), `docs/architecture.md` (schema v2, scanner
  layering, new fixtures), `fixtures/README.md` final; string parity green.
- [ ] **T28 — Device measurements** `[shared]` (X2; §8)
  Scan → confirmed and sustained rate on an iPhone and on the Android device the user names;
  APK size delta. `reports/bulk-scan/README.md`.
- [x] **T29 — Bind the BDPM lookup** `[shared]` (L1, L5) — *done after #7 merged: my duplicate `ProductLookup`/`ReferenceProduct` removed, `AppEnvironment.reference` bound on both platforms*
  Replace `UnknownProductLookup` with the #11 lookup on both platforms after rebasing on
  `develop`; flow 1 of §7 tested with a real reference GTIN.
- [x] **T30 — Versions and gate** `[shared]` (X6)
  iOS 0.2.0 (build 2), Android 0.2.0 (versionCode 2), changelog entries;
  `bash scripts/local-ci.sh` green. Coverage stated in the PR.
