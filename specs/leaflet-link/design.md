# Official leaflet link — Design

Status: **Pre-approved 2026-10-20** (implementation waits for the user's go)
Requirements: [`requirements.md`](requirements.md) · Issue: #56

## 1. Overview

Three pieces, in dependency order:

1. **Reference data** keeps the CIS: reference schema v2, built by `scripts/build-reference-db.py`, read by both
   apps (R1–R6).
2. **One shared rule** turns a reference product into a link address, or none: `LeafletLink` in both cores, proven
   by `fixtures/leaflet-links.json` (L2, L3).
3. **Box detail** looks the box's code up off the main thread and shows the link when there is one (L1, L4–L8).

Nothing changes in the cabinet database, the export format, the scan path or the permissions.

## 2. Reference database v2

### 2.1 Schema

```sql
CREATE TABLE presentation (
  cip13          TEXT PRIMARY KEY NOT NULL CHECK (length(cip13) = 13 AND cip13 GLOB '3400[0-9]*'),
  name           TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 255),
  category       TEXT NOT NULL,
  withdrawnSince TEXT CHECK (withdrawnSince IS NULL OR withdrawnSince GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
  cis            TEXT CHECK (CASE WHEN cis IS NULL THEN withdrawnSince IS NOT NULL
                                  ELSE length(cis) = 8 AND cis GLOB '[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]' END)
) WITHOUT ROWID;
```

- `cis` is the **last** column, so v1 readers' `SELECT name, category` and the existing row order stay readable. The
  apps never read a v1 database once they support v2 (they ship their own bundle), but the build script reads the
  committed v1 bundle once as the "previous" database.
- The CHECK is a `CASE`, not `… OR …`: SQLite accepts a CHECK that evaluates to NULL, so a NULL CIS on a current row would
  pass the `OR` form.
- `TEXT`, not `INTEGER`: a CIS is an identifier, and keeping the digits as text avoids any leading-zero or
  formatting question. Measured cost, un-vacuumed: about +209 KB on 20 896 rows (about 1.95 MB in total, budget
  8 MB).
- `PRAGMA user_version = 2`. The manifest's `referenceSchemaVersion` becomes `2`. Nothing else in the manifest
  changes shape.

### 2.2 Build script changes (`scripts/build-reference-db.py`)

| Piece | Change |
|-------|--------|
| `REFERENCE_SCHEMA_VERSION` | `2` |
| `SCHEMA_SQL` | §2.1 |
| `Row` | adds `cis: str \| None` |
| `classify_snapshot` | keeps the CIS already held in `Snapshot.presentations` (`{cip13: cis}`) instead of dropping it |
| `merge` | a current row takes the snapshot's CIS. A row carried over as withdrawn keeps the previous row's CIS (none from a v1 database) |
| `read_previous` | accepts `user_version` **1 or 2**. A v1 database is read without `cis` (`cis = None`). Any other version still fails |
| `read_database` | selects `cis` |
| `write_database` | 5-value insert |
| `content_sha256` | row line becomes `row\t{cip13}\t{name}\t{category}\t{withdrawnSince or ''}\t{cis or ''}` |
| `verify_bundle` | unchanged logic. It already checks `user_version`, the manifest version and the checksum against the constants above |

The script still uses only the standard library and still treats its input as untrusted. The CIS is already
validated by `CIS_PATTERN` (`[0-9]{8}`) when `CIS_CIP_bdpm.txt` is read.

### 2.3 Apps

**Swift core** (`Reference/ProductLookup.swift`):

```swift
public struct ReferenceProduct: Hashable, Sendable {
    public let name: String
    public let category: CategoryID?
    /// BDPM's identifier of the medicine; `nil` when the reference does not hold it (withdrawn presentations).
    public let cis: String?
    public let isWithdrawn: Bool
    public init(name: String, category: CategoryID? = nil, cis: String? = nil, isWithdrawn: Bool = false)
}
```

**Kotlin core** (`reference/ProductLookup.kt`): `data class ReferenceProduct(val name: String, val category:
CategoryID? = null, val cis: String? = null, val isWithdrawn: Boolean = false)`.

The defaults keep every existing call site and test double compiling unchanged.

**iOS** (`Persistence/Reference/ReferenceDatabase.swift`): `supportedSchemaVersion = 2`; `product(cip13:)` selects
`name, category, cis, withdrawnSince` and sets `isWithdrawn = withdrawnSince != nil`.

**Android** (`persistence/reference/ReferenceDatabase.kt`): `SUPPORTED_SCHEMA_VERSION = 2`; the same `SELECT`;
`contentSha256()` builds the row line exactly as §2.2. It must stay byte-identical to Python, or the installer
reports the bundled copy as `UNREADABLE`. `ReferenceDataTest`-style tests already compare the checksum of the
committed sample with its manifest, so a mismatch fails the suite.

A v1 database anywhere (a stale Android copy in `noBackupFilesDir`) is refused as `unsupportedSchema` or replaced by
the installer, which compares the bundled manifest's checksum. The Android installer already reinstalls when the
checksum differs, so an upgraded app replaces its v1 copy on first lookup.

### 2.4 Fixtures and committed data

- `scripts/tests/reference_sample.py` already gives every sample product a CIS (`60000001`…). The regenerated
  `fixtures/reference-sample.sqlite` and `reference-sample-manifest.json` (via
  `python3 -I scripts/tests/write-reference-sample.py`) carry them. The withdrawn sample row `TESTRETIRE` keeps its
  snapshot-1 CIS, because the sample is built from scratch in two snapshots, both at v2.
- `fixtures/reference-lookup.json`: every found case's `expect.product` gains `cis` and `isWithdrawn`. The
  `CommittedDataTests` comparison (`test-build-reference-db.py` around l.716) compares those keys too.
- `reference-data/bundle/`: rebuilt with `download` → `build --data-date <today>` → `verify`. This is a **data
  update**: the BDPM files are newer than 2026-10-18, so the report (new, withdrawn, renamed, recategorized) goes in
  the commit body as `docs/reference-data.md` requires, and the About screen shows the new date.

## 3. The link rule (both cores)

### 3.1 `LeafletLink`

```swift
// MediCabinetCore/Reference/LeafletLink.swift
public enum LeafletLink {
    public static let host = "base-donnees-publique.medicaments.gouv.fr"
    /// The BDPM page of the medicine (fact sheet, leaflet, SmPC), or `nil` when there is none to show.
    public static func address(for product: ReferenceProduct?) -> String?
}
```

```kotlin
// core/reference/LeafletLink.kt
object LeafletLink {
    const val HOST = "base-donnees-publique.medicaments.gouv.fr"
    fun address(product: ReferenceProduct?): String?
}
```

Rule: `nil` when the product is `nil`, is withdrawn, has no CIS, or its CIS is not exactly 8 characters in
`0`–`9` (ASCII). Otherwise `https://base-donnees-publique.medicaments.gouv.fr/medicament/<CIS>/extrait`.

- The digit check is by **code unit**, not `Character.isNumber` / `Char.isDigit`. Those accept non-ASCII digits
  such as `٦٠٢٣٤١٠٠`, which a fixture case covers. The database already refuses them; the core does not rely on
  that.
- The core returns a `String`, because Kotlin `:core` has no `android.net.Uri`. The apps build `URL(string:)` /
  `Uri.parse` from it. A non-`nil` address is always a valid absolute `https` URL.

### 3.2 `fixtures/leaflet-links.json`

```json
{
  "formatVersion": 1,
  "description": "LeafletLink.address(product) …",
  "cases": [
    { "name": "active medicine", "product": { "cis": "60234100", "isWithdrawn": false },
      "expect": "https://base-donnees-publique.medicaments.gouv.fr/medicament/60234100/extrait" },
    { "name": "no product", "product": null, "expect": null }
  ]
}
```

Cases: active; leading zero (`06000001`); no product; no CIS; withdrawn with a CIS; withdrawn without; 7 digits;
9 digits; a letter; a space inside; leading/trailing space; empty; Arabic-Indic digits; full-width digits; a `/`
(path injection). The description and a README row are added, and `scripts/tests/test-shared-fixtures-break.sh`
gains a broken case that both suites must fail.

## 4. iOS

### 4.1 Layers touched

`MediCabinetCore` (`ReferenceProduct`, `LeafletLink`) → `Persistence/Reference/ReferenceDatabase.swift` →
`Features/Cabinet/BoxDetailViewModel.swift` → `BoxDetailView.swift`. The view never sees the reference port: the
view model reads `environment.reference`, which is the `ReferenceDataSource` port (an actor, so the SQLite read is off
the main actor).

### 4.2 View model

```swift
/// The box's official BDPM page, once its code has been looked up; `nil` while looking up or when there is none.
private(set) var leaflet: URL?
private var leafletGTIN: String??   // the code `leaflet` was computed for, so a box update does not look up again
```

`followBox()` calls `refreshLeaflet(for: box)` when the box's GTIN differs from `leafletGTIN`. It runs
`try? await environment.reference.product(gtin:)`, then `LeafletLink.address(for:)` and `URL(string:)`. A thrown
lookup error gives no link: the reference layer already logs the failure kind. No GTIN means no lookup.

`func openLeafletFailed()` shows `box.leaflet.openFailed` through `undo?.show(_:)`, the plain-message form of the
undo banner already used on this screen's tab.

### 4.3 View

A `Section` after the information section and before `takeOutSection`, present only when `model.leaflet != nil`. A
section appearing below the information never moves a control above it (L4).

```swift
Section {
    Button { openURL(url) { accepted in if !accepted { model.openLeafletFailed() } } } label: {
        Label("box.leaflet.open", systemImage: "arrow.up.right.square")
    }
    .frame(minHeight: 44)
    .accessibilityAddTraits(.isLink)
    .accessibilityRemoveTraits(.isButton)
    .accessibilityHint(Text("box.leaflet.hint"))
    .accessibilityIdentifier("box.leaflet")
} footer: {
    Text("box.leaflet.note")
}
```

`@Environment(\.openURL)` is used rather than `Link` because `Link` gives no result to detect a failed open (L5). The
icon is part of the `Label`, which VoiceOver reads as its title only.

## 5. Android

### 5.1 Layers touched

`:core` (`ReferenceProduct`, `LeafletLink`) → `persistence/reference/ReferenceDatabase.kt` →
`features/cabinet/BoxDetailViewModel.kt` → `BoxDetailScreen.kt`. `features/cabinet` uses the core
`ReferenceDataSource` port only, which satisfies the `views_not_persistence` architecture rule.

### 5.2 View model

```kotlin
/** The box's official BDPM page, or null while looking up or when there is none. */
val leaflet: StateFlow<String?> = boxes.observeCabinet()
    .map { all -> all.firstOrNull { it.id == boxId }?.product?.gtin }
    .distinctUntilChanged()
    .mapLatest { gtin -> gtin?.let { LeafletLink.address(lookup(it)) } }
    .flowOn(dispatcher)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)
```

`lookup` calls `environment.reference.product(gtin)`. It rethrows `CancellationException` and turns every other
error into `null`, logging only through `AppLog` with the exception class. The flow is separate from `state`, so the
existing `BoxDetailState` and its tests are unchanged.

### 5.3 Screen

In `BoxDetailContent`, after the lot and added rows and before `TakeOutMenu`, when `leaflet != null`:

- a `ListItem`-style row: `Icons.AutoMirrored.Outlined.OpenInNew` (decorative, `contentDescription = null`) and the
  label `box_leaflet_open`. Modifiers: `Modifier.heightIn(min = 48.dp).clickable(onClickLabel =
  stringResource(R.string.box_leaflet_hint), role = Role.Button) { … }.testTag("box.leaflet")`;
- then the note `box_leaflet_note` (`bodySmall`, `onSurfaceVariant`).

The click runs `runCatching { uriHandler.openUri(address) }`. On failure it calls
`LocalUndoController.current?.notify(message)`, with `message = stringResource(R.string.box_leaflet_openFailed)` read
during composition (not inside the click lambda). This is the About link's mechanism
(`ACTION_VIEW` through `LocalUriHandler`). It needs no `<queries>` block and no permission.

## 6. Strings (both platforms, identical wording)

| Key (iOS / Android `_`) | fr | en |
|-------------------------|----|----|
| `box.leaflet.open` | Fiche officielle et notice | Official page and leaflet |
| `box.leaflet.hint` | Ouvre dans le navigateur | Opens in the browser |
| `box.leaflet.note` | Page de la base de données publique des médicaments, ouverte dans le navigateur. | Page of the French public medicines database, opened in the browser. |
| `box.leaflet.openFailed` | La page n'a pas pu être ouverte. | The page could not be opened. |

No platform-only string. No plural.

## 7. Flow

1. The user opens a box's detail. The box and its information appear from the cabinet observation as today.
2. The view model sees the box's GTIN for the first time and asks `ReferenceDataSource.product(gtin:)` off the
   main thread. The query is one primary-key read and takes well under 50 ms.
3. `LeafletLink.address` gives the page address or `nil`. When there is an address, the link section appears below
   the information.
4. A tap hands the URL to the system (`openURL` / `ACTION_VIEW`). The browser loads the page. The app sends nothing.
5. If the system refuses, the screen shows *The page could not be opened*.

## 8. Performance, privacy, data

- **Performance.** One indexed read per box-detail opening, off the main thread. Not on the scan path, not on cold
  start (the reference database still opens lazily). App size grows by about 0.2 MB, measured and stated in the PR.
- **Privacy.** No permission, no network API (the `no_network` SwiftLint rule and the Android architecture rule
  are unchanged and still pass). `PrivacyTest` still asserts no `INTERNET`. Nothing new is logged. The CIS, GTIN and
  name never appear in a log line. The browser request carries only the CIS in the path, after a tap.
  `PrivacyInfo.xcprivacy` is unchanged: no new required-reason API.
- **Data.** No cabinet migration. Export `formatVersion` unchanged. Reference schema 1 → 2 (bundled, replaced with
  the app).

## 9. Test strategy

| Requirement | Test |
|-------------|------|
| R1, R2 | `test-build-reference-db.py`: the column list is now the five columns; the schema refuses a 7-digit CIS, a non-digit CIS, and a null CIS on a current row, and accepts a null CIS on a withdrawn row; `user_version` and the manifest say 2 |
| R3 | build against a v1 previous database: current rows get the snapshot CIS, a carried-over withdrawn row has none, the build succeeds; a v3 previous database still fails |
| R4 | `content_sha256` covers `cis` (a changed CIS changes the checksum); Android `ReferenceDatabase.contentSha256()` equals the committed sample manifest (`ReferenceInstallerTest`); `verify` passes on the committed bundle |
| R5 | `CommittedDataTests` size budget; size measured in the PR |
| R6 | `reference-lookup.json` loops on both platforms (`ReferenceDataTests.swift`, `SqlReferenceDataTest.kt`) check `cis` and `isWithdrawn`; the bundled Doliprane `03400935955838` returns its CIS; the `unsupportedSchema` tests move from `user_version = 2` to `3` |
| L2, L3 | `LeafletLinkTests` / `LeafletLinkTest` run every `leaflet-links.json` case; `test-shared-fixtures-break.sh` proves both suites fail on a wrong expectation |
| L1, L3, L4 | iOS `BoxDetailViewModelTests`: a known GTIN gives the URL; unknown, withdrawn, no GTIN and a throwing lookup give `nil`; an edit of the box (same GTIN) does not look up again (counting fake). Android `BoxDetailViewModelTest`: the same cases with Turbine |
| L1, L7 | iOS UI test (`CabinetUI` seed with a GTIN known to `FixedProductLookup`, extended with a CIS): the `box.leaflet` element exists, is a link, has the label and hint, is ≥ 44 pt high, and is absent for a box without a code; largest text size. Android Robolectric `BoxDetailScreenTest`: the row shows with label, click label and ≥ 48 dp; absent without a code; font scale 2 |
| L5 | iOS: `openLeafletFailed()` shows the message (VM test). Android: a `LocalUriHandler` that throws → the snackbar message (Robolectric) |
| L2 | Android Robolectric: a click hands exactly the fixture address to a fake `UriHandler` |
| X2 | existing `PrivacyTest`, `no_network` lint fixtures, architecture checks: unchanged and green |
| X4 | string checks and parity check in the gate |

## 10. Docs and version

- `docs/reference-data.md`: schema v2 block, the `cis` column and why, the checksum line, the new data date and
  counts, and a pointer to the leaflet link.
- `docs/cabinet.md` (Box detail): the link, when it shows, what it opens.
- `fixtures/README.md`: `leaflet-links.json` row; `reference-lookup.json` fields.
- `specs/reference-data/requirements.md` B2 amendment note (one line pointing to this spec).
- iOS and Android minor bump (`0.x+1.0` after whatever develop holds when this branch is rebased), with changelog
  entries for the leaflet link and the data date.

## 11. Risks and alternatives considered

- **BDPM changes its URLs again.** The page moved from `extrait.php?specid=` to `/medicament/<CIS>/extrait`, and the
  old form redirects today. If the new form breaks, a host- and path-only change to `LeafletLink` plus a fixture
  update fixes both apps. A manual check of the address belongs to every data update (added to the procedure in
  `docs/reference-data.md`).
- **A presentation's page is missing although it is not withdrawn.** BDPM would show its own "not found" page. This
  is acceptable: the app makes no claim about the page's content.
- **Vidal** was rejected (§ decisions in requirements). **An in-app `SFSafariViewController` / Custom Tab** was
  rejected: it keeps the user in the app but adds a browser surface and, on Android, a dependency. The system
  browser is what About already does.
- **Storing the CIS on the box** was rejected: it would change the cabinet schema and the export for data the
  reference already holds.
