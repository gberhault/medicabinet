# Bulk scan intake — Design

Status: **Approved 2026-10-19**
Epic: #12 · Requirements: [`requirements.md`](requirements.md) · Platforms: iOS + Android

The rules (parsing, debouncing, intake decisions, product resolution) are written **once as
shared fixtures** and implemented **twice in the cores**. The apps only add the camera, the
screens and the wiring. Section numbers are referenced from `tasks.md`.

## 1. Key decisions

### 1.1 Android decoder: zxing-cpp on CameraX

| Option | Licence / maintenance | Privacy | DataMatrix / GS1 | Verdict |
|--------|-----------------------|---------|------------------|---------|
| Google ML Kit barcode scanning (bundled) | Proprietary Google terms | The ML Kit terms say the APIs "send metrics about the performance and utilization of the APIs in your app to Google", and the app must tell users. Breaks "no analytics / no network" | Very good | **Rejected** |
| Google Code Scanner (Play services) | Proprietary | Same as ML Kit; Google's own UI, one scan per launch | — | **Rejected** (not continuous) |
| ZXing Java (`com.google.zxing:core`) | Apache-2.0, maintenance mode | Offline | Weak on small/curved DataMatrix | Rejected (accuracy) |
| **zxing-cpp Android wrapper** (`io.github.zxing-cpp:android`, 3.x) | **Apache-2.0**, actively released | Offline, no permission | Strong DataMatrix, reports GS1 content type and symbology identifier | **Chosen** |

- CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`; AndroidX, Apache-2.0) supplies
  the preview and an `ImageAnalysis` use case (`STRATEGY_KEEP_ONLY_LATEST`, background
  executor). Each analysed frame goes to zxing-cpp's `BarcodeReader.read(ImageProxy)` with
  `formats = {DATA_MATRIX, EAN_13}` and `textMode = PLAIN`, which keeps GS as ASCII 29 (to be
  confirmed by the capture task, T6). The `symbologyIdentifier` (`]d2`) is prepended to the
  payload so the parser sees the same shape as on iOS.
- zxing-cpp ships a native library per ABI. Release builds keep `arm64-v8a`, `armeabi-v7a`
  and `x86_64` (emulator). The APK size increase is measured in T5 and recorded in the PR
  (limit: the 50 MB app-size target).
- Before adding it (T5), check and record in the PR: licence file, last release date
  (< 12 months), merged manifest adds no permission (`PrivacyTest`), no network classes.

### 1.2 iOS scanner: VisionKit `DataScannerViewController`

- `recognizedDataTypes: [.barcode(symbologies: [.dataMatrix, .ean13])]`,
  `qualityLevel: .balanced`, `recognizesMultipleItems: true`, `isHighFrameRateTrackingEnabled:
  true`, `isHighlightingEnabled: true`, guidance off (our own hint).
- The payload is `RecognizedItem.Barcode.payloadStringValue`. If T6 shows that VisionKit drops
  the GS separators, the adapter switches to `observation.payloadData` (iOS 17) and the spec
  is updated first.
- `DataScannerViewController.isSupported` (hardware) and `isAvailable` (permission and
  restrictions) drive the unsupported / denied states. All iOS 17 devices are supported; the
  check still exists for safety.

### 1.3 Box tombstone instead of hard delete (schema v2)

A mistaken scan must disappear, but the sync decision forbids hard deletes. v2 adds
`box.deletedAt`. A deleted box is invisible to every current query and stays in the file for
the future sync and export. "Removed" (used up / thrown away, a stock movement) and "deleted"
(never existed) stay separate concepts.

### 1.4 Small preferences on the platform key-value store

The session location and the sound toggle are UI preferences, not cabinet data:
`UserDefaults` on iOS (keys `scan.lastLocationID`, `scan.soundEnabled`) and
`SharedPreferences` named `scan_preferences` on Android (same keys; internal storage). They
are not exported and not part of the cabinet backup. iOS: `PrivacyInfo.xcprivacy` gains
`NSPrivacyAccessedAPICategoryUserDefaults` with reason `CA92.1`.

### 1.5 Sound

A short confirmation tone generated for the project (no third-party asset), bundled as
`scan_confirm.caf` (iOS, played with `AudioServicesPlaySystemSound` on a registered sound ID —
respects the silent switch) and `res/raw/scan_confirm.ogg` (Android, `SoundPool` on the
`USAGE_ASSISTANCE_SONIFICATION` stream).

## 2. Shared fixtures

All follow the existing shape (`formatVersion`, `description`, named `cases`) and are checked
by `check-fixtures.py`. A GS separator is written `\u001d` in JSON.

### 2.1 `fixtures/gs1-datamatrix.json` (P)

```json
{ "name": "full DataMatrix with ]d2 and GS after the lot",
  "payload": "]d2010340093000001417270300101A2B3C\u001d21XYZ123456789",
  "shape": "]d2 01 17 10(6) GS 21(12)",
  "expect": { "gtin": "03400930000014", "symbology": "dataMatrix",
              "expiry": { "year": 2027, "month": 3, "day": null },
              "lot": "1A2B3C", "serial": "XYZ123456789" } }
{ "name": "AI 17 with month 13 is invalid, never clamped",
  "payload": "010340093000001417271315",
  "shape": "01 17",
  "expect": { "gtin": "03400930000014", "symbology": "dataMatrix",
              "expiry": { "invalid": "invalidMonth" }, "lot": null, "serial": null } }
{ "name": "wrong check digit", "payload": "0103400930000015", "shape": "01", "expect": { "error": "invalidGTIN" } }
```

- `expiry` is `{year, month, day|null}`, `"missing"`, or `{ "invalid": <ExpiryDate error kind | invalidFormat> }`. `shape` sits at case level (it exists for error cases too; `empty`, `tooLong(n)` and `invalidCharacters` for the three rejected-before-reading errors).
- `shape` (P14) tokens: optional prefix (`]d2`, `]Q3`, `]C1`), `FNC1` for a leading GS, each
  AI (fixed-length AIs bare, variable-length AIs with their length `10(6)`), `GS` where present,
  `?(n)` for an unknown AI and the `n` characters left, `EAN13` for a 13-digit payload.
  Values never appear in a shape.
- Seed cases (anonymised GTINs with valid check digits, invented lots/serials): every AI order
  seen on French boxes (01 17 10 21, 01 21 17 10, 01 10 GS 17 21…); with and without `]d2`,
  leading FNC1, trailing GS, GS after a fixed field; DD=00 in Feb of a leap and a non-leap
  year; day 31 in a 30-day month; year 00 and 99; AI 15/16 only (expiry missing); GS missing
  before AI 17 (expiry missing, lot swallows the rest or is dropped when > 20 chars);
  lot/serial 20 and 21 chars; lot with a character outside CSET 82; unknown AI (`240`) before
  AI 17; EAN-13 CIP13 `3400…` and ACL `3401…`; 12 and 15 digits; empty; 513 characters; NUL
  and LF characters; no AI 01. Then every real payload shape captured in T6.

### 2.2 `fixtures/scan-debounce.json` (D)

```json
{ "name": "a box held still counts once, then again after a 1 s absence",
  "rescanGapMs": 1000,
  "frames": [ { "t": 0, "visible": ["A"] }, { "t": 400, "visible": ["A"] },
              { "t": 500, "visible": [] }, { "t": 900, "visible": ["A"] },
              { "t": 1000, "visible": [] }, { "t": 2100, "visible": ["A"] } ],
  "expectScans": [ { "t": 0, "payload": "A" }, { "t": 2100, "payload": "A" } ] }
```

Cases: flicker shorter than the gap; absence of exactly the gap (counts: `>=`); two codes in
one frame (both, first-seen order); A, then B, then A again within the gap (A is not counted
again: absence is measured per payload); empty frames only; clock not advancing.

### 2.3 `fixtures/scan-intake.json` (I, K)

```json
{ "name": "serial already in the cabinet",
  "scan": { "hasSerial": true, "expiry": "known" }, "productKnown": true,
  "cabinetMatch": "inCabinet", "sessionHasGTIN": false,
  "expect": { "decision": "alreadyInCabinet" } }
{ "name": "no serial, same product earlier in the session, unknown date",
  "scan": { "hasSerial": false, "expiry": "missing" }, "productKnown": true,
  "cabinetMatch": null, "sessionHasGTIN": true,
  "expect": { "decision": "confirmAnother", "thenMissing": ["expiry"] } }
```

- `expiry`: `known | missing | invalid`; `cabinetMatch`: `null | inCabinet | removed` (a
  non-deleted box with the same GTIN and serial).
- `decision` (cross-platform names): `add`, `complete`, `confirmAnother`,
  `alreadyInCabinet`, `removedEarlier`. `complete` and `confirmAnother` carry `missing` /
  `thenMissing` ⊆ `["name", "expiry"]` and `expiryUnreadable: true` when the expiry was invalid.
- Cases cover the full matrix (2 × 3 × 2 × 3 × 2 = 72 combinations are reduced to the
  meaningful ones: with a serial, `sessionHasGTIN` is irrelevant; with no serial,
  `cabinetMatch` is always null).

### 2.4 `fixtures/product-resolution.json` (L)

```json
{ "name": "reference name wins, user category from the previous box is kept",
  "reference": { "name": "DOLIPRANE 1000 mg cp" },
  "previousBox": { "name": "Doliprane", "category": "pain-fever", "categoryOverridden": true },
  "expect": { "name": "DOLIPRANE 1000 mg cp", "category": "pain-fever", "categoryOverridden": true } }
```

`reference` and `previousBox` may be null; `expect.name` null means unknown product.

### 2.5 Schema and repository contract (V)

- `fixtures/schema-v2.sql` — the v2 migration step only (§4.1), text-compared with each
  platform's copy, like `schema-v1.sql`.
- `fixtures/schema-v2.json` — the full schema after v1 + v2 (same format as `schema-v1.json`);
  the schema tests introspect a freshly migrated database against it. `schema-v1.json` stays
  and is checked against a database migrated to v1 only (upgrade test).
- `fixtures/repository-contract.json` gains the checks of §4.2.
- `fixtures/README.md` lists the new files and names: parse error kinds (`empty`, `tooLong`,
  `invalidCharacters`, `missingGTIN`, `invalidGTIN`), intake decisions, repository error
  `duplicateSerial`.
- `scripts/tests/test-shared-fixtures-break.sh` gains a broken copy of each new fixture.

## 3. Core (both platforms)

New files in `MediCabinetCore` (`Sources/MediCabinetCore/Scan/`) and `:core`
(`com.medicabinet.core.scan`). Same names on both; Kotlin uses `Instant`, `UUID`, `suspend`
and exceptions where Swift uses `Date`, `UUID`, `async` and typed throws.

### 3.1 Parsing (P)

```swift
public enum Symbology: String, Sendable { case dataMatrix, ean13 }

public enum ExpiryReading: Hashable, Sendable {
    case date(ExpiryDate)
    case missing
    case invalid(kind: String)          // an ExpiryDateError kind
}

public struct ScannedCode: Hashable, Sendable {
    public let gtin: String             // GTIN-14, check digit verified
    public let expiry: ExpiryReading
    public let lot: String?
    public let serial: String?
    public let symbology: Symbology
}

public enum ScanParseError: Error, Equatable, Sendable {
    case empty, tooLong, invalidCharacters, missingGTIN, invalidGTIN
    public var kind: String { … }       // fixture names
}

public enum GS1Parser {
    public static let maxPayloadLength = 512
    public static func parse(_ payload: String) throws(ScanParseError) -> ScannedCode
    public static func shape(of payload: String) -> String     // P14, never values
}

public enum GTIN {
    public static func normalized(_ digits: String) -> String?  // 13 → "0"+13, 14 → 14, check digit verified
}
```

Algorithm (one pass, index-based, no regex):
1. Length and character checks (P12). Strip a symbology prefix, then leading GS characters.
2. If the remainder is 13 digits → EAN-13 (P4).
3. Loop: read a 2-digit AI (3 digits for the `24x`, `71x` families listed in the table).
   Fixed-length table: `00`(18) `01`(14) `02`(14) `11` `12` `13` `15` `16` `17`(6).
   Variable (≤ max, end at GS or end of payload): `10`(20) `21`(20) `22`(20) `30`(8)
   `240`(30) `241`(30) `710`–`715`(20). Any other AI → stop (P11). Skip a GS after a fixed
   field (P3).
4. AI `17`: `ExpiryDate.make(year: 2000+YY, month: MM, day: DD == 0 ? nil : DD)`; an error
   gives `.invalid(kind)` (P6, P7). AI `15`/`16` are read and ignored (P9).
5. Lot/serial pass CSET 82 and ≤ 20, else dropped (P10). A duplicated AI keeps the first value.
6. No AI `01` → `missingGTIN`; bad GTIN → `invalidGTIN` (P5).

### 3.2 Debouncing (D)

```swift
public struct ScanDebouncer: Sendable {
    public static let defaultRescanGap: Duration = .seconds(1)
    public init(rescanGap: Duration = defaultRescanGap)
    /// Feed every frame/snapshot; returns the payloads that count as new scans, in first-seen order.
    public mutating func observe(visible: [String], at instant: Date) -> [String]
}
```

State per payload: `lastSeen`. A payload is a new scan when it was never seen, or when
`instant - lastSeen >= rescanGap` (it was absent long enough). `lastSeen` is updated on every
frame where the payload is visible. Entries older than 10 × gap are evicted (bounded memory).

### 3.3 Product resolution (L)

```swift
public struct ReferenceProduct: Hashable, Sendable { public let name: String; public let category: CategoryID? }

public protocol ProductLookup: Sendable {
    func product(gtin: String) async throws -> ReferenceProduct?     // nil = unknown
}

public struct ResolvedProduct: Hashable, Sendable {
    public let name: String?                 // nil = unknown, ask (K4)
    public let category: CategoryID
    public let categoryOverridden: Bool
}

public enum ProductResolver {
    public static func resolve(reference: ReferenceProduct?, previousBox: Box?) -> ResolvedProduct
}
```

Precedence: name = reference ?? previous box ?? unknown; category = previous box's category
when it was user-overridden, else reference category, else `uncategorized`.

### 3.4 Intake decision and service (I, K, R)

```swift
public enum IntakeField: String, Sendable { case name, expiry }

public enum IntakeDecision: Hashable, Sendable {
    case add
    case complete(missing: Set<IntakeField>, expiryUnreadable: Bool)
    case confirmAnother(thenMissing: Set<IntakeField>, expiryUnreadable: Bool)
    case alreadyInCabinet
    case removedEarlier
    public var kind: String { … }            // fixture names
}

public enum IntakePolicy {                   // pure, fixture-driven (§2.3)
    public static func decide(code: ScannedCode, productKnown: Bool,
                              cabinetMatch: Box?, sessionHasGTIN: Bool) -> IntakeDecision
}

/// What will become a box; the app edits it in the completion step and the review list.
public struct BoxDraft: Hashable, Sendable {
    public var gtin: String?
    public var name: String?
    public var category: CategoryID
    public var categoryOverridden: Bool
    public var expiry: ExpiryDate?           // nil = unknown, only after an explicit choice
    public var lot: String?
    public var serial: String?
}

public enum IntakeOutcome: Sendable {
    case ready(BoxDraft)                                  // add immediately
    case needsCompletion(BoxDraft, missing: Set<IntakeField>, expiryUnreadable: Bool)
    case confirmAnother(BoxDraft, thenMissing: Set<IntakeField>, expiryUnreadable: Bool)
    case alreadyInCabinet(Box)
    case removedEarlier(Box)
}

public struct ScanIntake: Sendable {
    public init(boxes: any BoxRepository, lookup: any ProductLookup, clock: any Clock)
    public func evaluate(_ code: ScannedCode, sessionGTINs: Set<String>) async throws -> IntakeOutcome
    public func manualDraft(code: String?, name: String?) async throws -> BoxDraft  // M2: typed code resolved, invalid → error
    /// Validates (name 1…200 trimmed), stamps id/createdAt, inserts. `.duplicateSerial` → caller shows "already in the cabinet".
    public func add(_ draft: BoxDraft, location: Location.ID) async throws -> Box
    public func putBack(_ box: Box, to location: Location.ID) async throws -> Box
    public func delete(_ id: Box.ID) async throws
    public func undoDelete(_ id: Box.ID) async throws
}
```

`evaluate` = `box(gtin:serial:)` (if serial) → `lookup.product` → `latestBox(gtin:)` →
`ProductResolver` → `IntakePolicy.decide` → outcome. The session state (GTINs added this
session, the review list) stays in the view model; `ScanIntake` is stateless.

## 4. Persistence (V)

### 4.1 Migration v2 (identical SQL, `fixtures/schema-v2.sql`)

```sql
ALTER TABLE box ADD COLUMN deletedAt DATETIME;
DROP INDEX box_serial;
CREATE UNIQUE INDEX box_serial ON box(gtin, serial) WHERE serial IS NOT NULL AND deletedAt IS NULL;
```

- iOS: `Migrations.v2` registered after `v1` in the GRDB migrator. Android: `Migrations.v2`
  appended (schema version 2, `PRAGMA user_version`). The existing bootstrap backs up before
  migrating and restores on failure (V2); the bootstrap tests gain a real v1 → v2 upgrade.
- 0.1.0 has no way to add a box, so no v1 cabinet can hold duplicate serials; if one did, the
  migration fails, the copy is restored and the migration error is shown (no data loss).
- `Box` (core model) is unchanged: deleted boxes are never returned. The export epic (#27)
  will read tombstones through its own query.

### 4.2 Repository port additions (both cores, fake, GRDB / SQL implementations)

```swift
public enum RepositoryError { …; case duplicateSerial }          // raw "duplicateSerial"

extension BoxRepository {
    /// Non-deleted box (in the cabinet or removed) with this GTIN and serial.
    func box(gtin: String, serial: String) async throws -> Box?
    /// Most recently created non-deleted box with this GTIN.
    func latestBox(gtin: String) async throws -> Box?
    /// Stamps deletedAt/updatedAt. Throws .notFound for an unknown or already deleted id.
    func delete(id: Box.ID) async throws
    /// Clears deletedAt. Throws .notFound when not deleted; .duplicateSerial when the serial is taken again.
    func undoDelete(id: Box.ID) async throws
}
```

`insert` and `update` map the unique-index violation to `.duplicateSerial`; `activeBoxes`,
`box(id:)`, `update`, `markRemoved`, `undoRemoval` treat deleted boxes as absent.

New contract checks (names added to `repository-contract.json`, in order):
`a deleted box disappears from every query`, `undoDelete restores a deleted box`,
`delete and undoDelete on unknown or wrong-state boxes throw notFound`,
`a second box with the same gtin and serial is rejected with duplicateSerial`,
`a deleted box frees its serial`, `box by gtin and serial returns removed boxes but not deleted ones`,
`latestBox returns the most recent non-deleted box of a gtin`.

## 5. iOS app

```
ios/MediCabinet/Sources/Features/Scan/
  ScanView.swift               # tab root: switches on ScanViewModel.state
  ScanViewModel.swift          # @MainActor @Observable; session, debouncer, outcomes
  ScanSession.swift            # session list entries, GTIN set, counts (value type)
  CameraPermissionView.swift   # explain / denied / unsupported states (C1, C3)
  ScannerOverlay.swift         # location chip, sound toggle, Done, hint, confirmation card
  CompletionSheet.swift        # K
  SessionListView.swift        # R1–R3 (+ RowEditor)
  ScanSummaryView.swift        # R4
  ManualEntryView.swift        # M
  ExpiryField.swift            # month / year / optional day, shared by K, R, M
  ScanFeedback.swift           # haptics (.sensoryFeedback), sound
  ScanPreferences.swift        # UserDefaults wrapper (protocol + live + in-memory)
  Camera/
    CodeScanner.swift          # protocol: AsyncStream<VisibleCodes>, start/stop
    VisionKitScanner.swift     # DataScannerViewController + UIViewControllerRepresentable
    InjectedCodeScanner.swift  # UI tests: payloads from launch environment
    PayloadCaptureView.swift   # #if DEBUG only (C10)
  ProductLookups.swift         # UnknownProductLookup (until #11)
```

- `VisibleCodes = (payloads: [String], at: Date)`. `VisionKitScanner` emits a snapshot on
  every change of `recognizedItems` (added / updated / removed). Payloads are passed through
  `GS1Parser.parse` in the view model, never logged.
- `ScanViewModel` states: `.permissionUndetermined`, `.permissionDenied(canOpenSettings)`,
  `.unsupported`, `.starting`, `.scanning`, `.cameraFailed`. Sub-state: `prompt` (completion,
  confirm-another, already-in-cabinet, removed-earlier, not-recognised) — while a blocking
  prompt (completion) is open, observed codes are ignored (C7).
- Camera lifecycle: `startScanning()` on `.onAppear` + `scenePhase == .active`, `stopScanning()`
  on `.onDisappear` / background (C7). Permission via `AVCaptureDevice.authorizationStatus` /
  `requestAccess`; **Open Settings** = `UIApplication.openSettingsURLString`.
- `MainTabView` replaces the Scan placeholder with `ScanView`; `AppEnvironment` gains
  `scanIntake: ScanIntake`, `preferences: any ScanPreferences`, `scannerFactory`.
- `Info.plist`: `NSCameraUsageDescription`, localised in `InfoPlist.xcstrings`.
- VoiceOver: `AccessibilityNotification.Announcement` for each added box ("Ajouté :
  %@, expire %@"); the camera preview is hidden from VoiceOver except its hint.
- SwiftLint custom rule: `import VisionKit` / `import AVFoundation` only under
  `Features/Scan/Camera/` (+ fixture in `scripts/tests/ios-lint/`).

## 6. Android app

```
android/app/src/main/kotlin/com/medicabinet/app/features/scan/
  ScanScreen.kt  ScanViewModel.kt  ScanSession.kt  CameraPermissionContent.kt
  ScannerOverlay.kt  CompletionSheet.kt  SessionListSheet.kt  ScanSummary.kt
  ManualEntrySheet.kt  ExpiryField.kt  ScanFeedback.kt  ScanPreferences.kt
  camera/
    CodeScanner.kt            # interface: Flow<VisibleCodes>
    CameraXScanner.kt         # PreviewView + ImageAnalysis + zxing-cpp BarcodeReader
    FakeCodeScanner.kt        # in src/test (Robolectric) and debug-injectable
    PayloadCaptureScreen.kt   # src/debug only (C10)
  ProductLookups.kt           # UnknownProductLookup (until #11)
```

- `ScanViewModel` mirrors iOS (same states, `StateFlow<ScanUiState>`), with an injected
  `CoroutineDispatcher` for repository work; frame decoding runs on the CameraX analysis
  executor, parsing and debouncing on `Dispatchers.Default`.
- Camera lifecycle: `CameraXScanner` binds to the composable's `LifecycleOwner` (stops on
  `ON_STOP`) and unbinds in `DisposableEffect` when the tab leaves composition (C7).
- Permission: `ActivityResultContracts.RequestPermission`; "denied permanently" =
  denied and `shouldShowRequestPermissionRationale` false → **Open Settings** with
  `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`. No camera
  (`PackageManager.FEATURE_CAMERA_ANY` false) → unsupported state.
- Manifest: `<uses-permission android:name="android.permission.CAMERA" />`,
  `<uses-feature android:name="android.hardware.camera.any" android:required="false" />`.
  `PrivacyTest` changes from "no system permission" to "exactly CAMERA, never INTERNET".
- Haptics: `View.performHapticFeedback(CONFIRM / REJECT)` on API 30+, `KEYBOARD_TAP` /
  `LONG_PRESS` below — no `VIBRATE` permission.
- TalkBack: `LiveRegionMode.Polite` on the confirmation card; 48 dp targets.
- Architecture rule (`android-check-architecture.py`): `androidx.camera` and `zxingcpp`
  imports only under `features/scan/camera/` (+ fixture).
- Dependencies (`libs.versions.toml`): `androidx.camera:*` 1.4.x/1.5.x stable, `zxing-cpp`
  `android` 3.x — exact versions pinned in T5.

## 7. Flows

1. **Complete DataMatrix, known product** — frame → debouncer emits payload → `parse` →
   `evaluate` (serial lookup, product lookup, latest box) → `.ready` → `add` (insert) →
   confirmation card + haptic (+ sound) + announcement → session list +1. Target < 300 ms.
2. **EAN-13 only** — `parse` gives expiry `.missing` → `.needsCompletion([expiry])` → camera
   ignores codes → sheet: month/year/day or **Date not printed** → `add` → resume.
3. **Serial already in the cabinet** — `.alreadyInCabinet(box)` → warning haptic, card
   "Déjà dans l'armoire — Salle de bain"; nothing written.
4. **Removed earlier** — `.removedEarlier(box)` → card with **Put back** → `putBack` (undo
   removal + session location) → success feedback.
5. **Same product, no serial, again** — `.confirmAnother` → non-blocking card "Another box of
   X? Add"; tap → completion if needed → `add`; next scan dismisses the card.
6. **Undo / delete** — card **Undo** or row delete → `delete(id)` → snackbar **Undo** →
   `undoDelete(id)` (`.duplicateSerial` → message, box stays deleted).
7. **Permission denied** — explain screen → denied → **Open Settings** / **Add without
   scanning** → manual form → `manualDraft` → `add`.

## 8. Performance

- Path cost: parse (µs) + 3 indexed queries (`box_serial`, `box_gtin`) + 1 insert + reference
  lookup (#11 budget < 50 ms). Budget for evaluate + add on a 1 000-box cabinet: **≤ 100 ms**
  (≥ 3× the expected cost), asserted over 10 runs (median) in an iOS app test (GRDB, temp
  file) and an Android Robolectric test (SQLite) — guarding the < 300 ms scan → confirmed
  target together with the UI work.
- `ScanDebouncer.observe` with 5 payloads × 10 000 frames: < 50 ms in a core test (no
  per-frame allocation growth).
- Measured once on devices in T28 (signpost / `Trace` sections around evaluate + add, no
  payload in the section name), result in `reports/bulk-scan/README.md`.
- Camera off when not visible (C7); no I/O on the main thread; queries indexed.

## 9. Privacy impact

| Item | iOS | Android |
|------|-----|---------|
| New permission | Camera (`NSCameraUsageDescription`, fr + en) | `CAMERA` runtime permission; hardware not required |
| Privacy manifest | `NSPrivacyAccessedAPICategoryUserDefaults` / `CA92.1` | — |
| Data collected / sent | none | none (no ML Kit; zxing-cpp is offline) |
| Logging | failure kinds only (`ScanParseError.kind`, repository error) | `AppLog` failure kinds only |
| Stored | boxes (existing table), two UI preferences | same |

Store labels stay "no data collected". The debug capture view shows shapes only, and only
in debug builds.

## 10. Test strategy

| Req | Test (iOS / Android) | Kind |
|-----|----------------------|------|
| P1–P12 | `GS1ParserFixtureTests` / `GS1ParserFixtureTest` (one per fixture case) + `GS1ParserTests` (GTIN check digit, AI table) | core, shared |
| P13 | `GS1ParserFuzzTests` (seeded random + every prefix of every fixture payload) | core |
| P14 | shape assertions in the fixture; "no value in shape" test | core, shared |
| D1–D5 | `ScanDebouncerFixtureTests` + perf test | core, shared |
| I1–I6, K1 | `IntakePolicyFixtureTests`; `ScanIntakeTests` (in-memory fake) | core, shared |
| L1–L4 | `ProductResolverFixtureTests`; `ScanIntakeTests` with a stub lookup | core, shared |
| I7, V1–V3 | `SchemaFixtureTests` (v2), `MigrationsTests` (v1 → v2 upgrade with data), `RepositoryContract` (new checks) on fake + GRDB / SQL; bootstrap upgrade test | integration |
| C1–C5, C7, C9 | `ScanViewModelTests` / `ScanViewModelTest` (fake scanner, fake permission, fake lifecycle); UI: `ScanUITests` (injected payloads) / Robolectric `ScanScreenTest` | unit + UI |
| C6 | view-model test: feedback event per outcome; perf test (§8) | unit + perf |
| C8 | preferences round-trip; toggle UI test | unit + UI |
| C10 | release build excludes capture view (iOS `#if DEBUG` compile test via lint rule; Android file in `src/debug`) | gate |
| K2–K5 | `ExpiryFieldTests` (validation, no prefill on invalid), completion UI test | unit + UI |
| R1–R4 | session list edit / delete / undo / summary: view-model + UI tests | unit + UI |
| M1–M4 | manual entry view-model + UI test with permission denied | unit + UI |
| X1 | `PrivacyTest` (exactly CAMERA), Info.plist key test, privacy manifest test, log-policy lint | unit + gate |
| X3 | UI tests at accessibility text size / font scale 2.0, labels present | UI |
| X4 | `ios-check-strings.sh`, `check-string-parity.py` | gate |

Every fixture-driven suite also asserts its file loads and is non-empty (as today).

## 11. i18n impact

New keys on both platforms (fr = source, en), same wording. Main groups:
`scan.permission.{title,message,allow,denied,openSettings,unsupported}`,
`scan.camera.{starting,failed,retry,hint}`, `scan.location.{label,change}`,
`scan.sound.{on,off}`, `scan.done`, `scan.count` (plural),
`scan.added.{title,expiry,expiryUnknown,undo,announcement}`,
`scan.duplicate.{inCabinet,removedEarlier,putBack}`, `scan.another.{question,add}`,
`scan.unrecognized`, `scan.complete.{title,name,expiry,expiryUnreadable,dateNotPrinted,add,cancel}`,
`expiry.field.{month,year,day,dayOptional,invalid}`,
`scan.session.{title,empty,edit,delete,deleted,undo,summary}` (plural),
`manual.{title,code,codeInvalid,name,add}`, iOS `NSCameraUsageDescription`.
Expiry dates are shown with `Date.FormatStyle` / `DateTimeFormatter` (month-year for month
precision).

## 12. Risks

- **Scanner payload shapes** (VisionKit GS handling, zxing-cpp `PLAIN` mode) are verified in
  T6 on real boxes; a surprise there changes §1.1–1.2 and the fixture, not the parser's rules.
- **Dependency on #11**: the epic PR waits for the BDPM lookup; the branch may need a rebase
  on `develop` after epic #7 merges (schema v2 vs any reference-data change).
- **Robolectric timing** is noisy: the §8 budgets are generous and use medians.
- **Native library size** (zxing-cpp) measured in T5.
