# Cabinet inventory, locations and stock movements — Design

Status: **Approved 2026-10-19**
Epic: #17 · Requirements: [`requirements.md`](requirements.md) · Platforms: iOS + Android

This design assumes epic #12 (bulk scan) is on `develop`: schema v2 (`box.deletedAt`,
`duplicateSerial`, `delete` / `undoDelete`, `box(gtin:serial:)`), `ExpiryField`, the scanner
adapters, `ScanViewModel`, `ScanPreferences` and the feedback helpers. Names from the
bulk-scan design are reused as they are. As in #12, the rules are written **once as shared
fixtures** and implemented **twice in the cores**; the apps add screens and wiring. Section
numbers are referenced from `tasks.md`.

## 1. Key decisions

### 1.1 Rows are computed in the core, in memory

The Cabinet list is not a SQL `GROUP BY`. The app observes the boxes in the cabinet (≤ a few
thousand rows) and a pure core function, `CabinetRows.build`, groups, filters, orders and
counts them. The reasons:

- Accent-insensitive matching and the product key cannot be expressed identically in SQLite
  on both platforms (`LIKE` folds ASCII case only; no ICU on Android's framework SQLite).
- Grouping, row status and ordering must match exactly on both platforms, so they belong to
  the fixture-tested cores (C5).
- The cost is O(n log n) on ~1 000 boxes, measured at a few milliseconds; the budget is
  ≤ 100 ms off the main thread (§8).

### 1.2 Expiry status is injected, not computed here

`CabinetRows.build` takes a `status: (Box) -> ExpiryStatus` function. The app binds it to
#23's core rule with today's date (from the injected `Clock`) and the user's "expiring soon"
window. This keeps the epic independent of #23's exact API: the fixtures give each box's
status directly. The app rebuilds rows when the day changes (on foreground, and at midnight
while visible) and when the window setting changes.

### 1.3 Live updates: one observation per platform

- **iOS**: GRDB `ValueObservation` (tracks the `box` / `location` tables, coalesces writes)
  exposed through the port as `AsyncThrowingStream`. GRDB stays inside `Persistence/`.
- **Android**: `CabinetDatabase` gains `changes: StateFlow<Long>`, a counter bumped after
  every committed `transaction {}`. All writes (repositories, the future import #29) go
  through `transaction`, so no write is missed. Repositories expose
  `changes.map { query() }.flowOn(io).conflate()`.
- Collection only while the screen is visible (`.task` on iOS,
  `collectAsStateWithLifecycle` + `WhileSubscribed(5 s)` on Android), so a bulk-scan session
  on the Scan tab does not re-query the cabinet on each box.

### 1.4 Text normalization is explicit, not a platform "fold"

Foundation's `folding(options:)` and Java's `Collator` disagree at the edges. Both cores
implement the same steps:

1. Unicode NFD (`decomposedStringWithCanonicalMapping` / `Normalizer.normalize(NFD)`).
2. Lower-case code point by code point, locale-independent (Swift `properties.lowercaseMapping`
   per scalar, Kotlin `Character.toLowerCase(codePoint)`), so a Turkish phone gives the same
   result and Greek final sigma is not context-dependent. Lowering comes before folding so
   `Œ`/`ẞ` reach step 4 already lower-cased.
3. Drop non-spacing marks (general category `Mn`; this also removes the dot `İ` leaves behind).
4. Fold ligatures and look-alikes: `œ`→`oe`, `æ`→`ae`, `ß`→`ss`, `’` / `‘`→`'`.
5. Any run of whitespace → one space; trim. *Whitespace* is the Unicode `White_Space` property,
   written out as an explicit code point set in both cores (U+0009–000D, 0020, 0085, 00A0,
   1680, 2000–200A, 2028, 2029, 202F, 205F, 3000) because `Character.isWhitespace` in Java
   excludes the no-break spaces.

`fixtures/text-normalization.json` pins the result. The same function serves search (S1),
location-name uniqueness (L2) and name ordering (C4: compare normalized names by code point,
then the box id's lower-case canonical string — never `UUID.compareTo`, which orders signed
longs — which is deterministic on both platforms). Normalized text is compared by Unicode code
point (not UTF-16 unit, not locale collation).

### 1.5 Location deletion: tombstone + move in one write

`delete(id:movingBoxesTo:)` runs in one transaction. It checks that the location is not the
default and not already deleted, and that the destination exists and is not deleted. It
moves the location's boxes **in the cabinet** (not removed, not deleted) to the destination,
stamping `updatedAt`, then stamps `location.deletedAt` / `updatedAt`. Removed and deleted
boxes keep their `locationId` (L9). The rules are enforced in the repository and proven by
contract checks, on the fake and the real store, on both platforms (V4). There is no SQL
trigger: the repository is the only writer, and adding triggers would mean extending the
schema-fixture format.

Undo (L7) exists only for an **empty** location: it clears `deletedAt` (same `sortOrder`, so
same place). A deletion that moved boxes is confirmed instead (L8). Undoing would mean
remembering and moving back an arbitrary set of boxes, which the confirmation already
guards.

### 1.6 Remove mode lives in the scanner from #12

Remove is a mode of the existing `ScanViewModel` (one camera, one debouncer, one lifecycle).
The mode is never persisted (R1). The last removal reason is a new UI preference
`scan.removalReason` (`usedUp` | `thrownAway`) in the #12 `ScanPreferences` store
(UserDefaults / `scan_preferences`). It is not exported and not backed up as cabinet data.

### 1.7 One undo component per platform

Removal, deletion and location deletion all use the same "message + **Undo**" component:

- **Android**: the Material 3 `Snackbar` in a `SnackbarHost` owned by `MainScaffold`
  (`SnackbarDuration.Long`, action label "Annuler" / "Undo"). TalkBack reads snackbars;
  the action is focusable.
- **iOS** has no system snackbar. An `UndoBanner` overlay sits above the tab bar (`.safeAreaInset`),
  with a named duration `UndoBanner.displayDuration = 10 s` matching Android's long
  duration. It posts `AccessibilityNotification.Announcement`, and VoiceOver users can reach
  it through a `.accessibilityAction(named: "Annuler")` on the screen. It does not
  auto-dismiss while VoiceOver focus is inside it.

The undo action is a closure captured at the time of the action, not global state; a new
action replaces the previous banner (its undo is no longer offered).

## 2. Shared fixtures

All follow the existing shape (`formatVersion`, `description`, named `cases`) and are checked
by `check-fixtures.py`. Box ids in fixtures are short labels (`"b1"`); each platform's test
maps them to UUIDs.

### 2.1 `fixtures/text-normalization.json` (S1, S2, L2, C4)

```json
{ "name": "accents, case and ligature fold", "input": "Œdème  ÉFFÉRALGAN 500",
  "normalized": "oedeme efferalgan 500" }
{ "name": "every query word must match, in any order", "kind": "match",
  "text": "DOLIPRANE 1000 mg cp", "query": "1000 doli", "matches": true }
{ "name": "empty query matches everything", "kind": "match", "text": "X", "query": "  ", "matches": true }
```

Cases: French accents (é è ê ë à â ç î ï ô û ù ü ÿ), upper-case accented letters, `œ` / `Œ` / `æ`,
`ß`, typographic apostrophe, NBSP and narrow NBSP, tabs and newlines, Turkish `İ` and `I`,
digits and `%`, a query word that only matches across a word boundary (does not match),
an emoji (kept).

### 2.2 `fixtures/location-names.json` (L2, L3)

```json
{ "name": "duplicate after normalization", "input": " cuísine ", "existing": ["Cuisine", "Salle de bain"],
  "expect": { "error": "duplicateName" } }
{ "name": "renaming to its own name with different case is allowed", "input": "CUISINE",
  "existing": ["Cuisine"], "renaming": "Cuisine", "expect": { "name": "CUISINE" } }
```

Errors: `empty`, `tooLong` (> 50 Unicode code points after trimming), `duplicateName`.
Length is counted in code points, not grapheme clusters: SQLite's `length()` (the v1 `CHECK`,
≤ 100) counts code points, so a code-point limit of 50 can never fail the database check,
and `BreakIterator` versions differ on emoji sequences. The same rule counts box names
(1…200 code points, B2) — the v1 `productName` check is also in code points.

### 2.3 `fixtures/cabinet-rows.json` (C1–C5, S1, S3, S4, P1)

```json
{ "name": "a row's status is its most urgent box; unknown never shows as ok",
  "categoryOrder": ["pain-fever", "cold-ent"],
  "boxes": [
    { "id": "b1", "gtin": "03400930000014", "name": "Doliprane 1000", "category": "pain-fever",
      "location": "bath", "expiry": { "year": 2027, "month": 3, "day": null }, "status": "ok", "createdOrder": 1 },
    { "id": "b2", "gtin": "03400930000014", "name": "DOLIPRANE 1000 mg cp", "category": "pain-fever",
      "location": "kitchen", "expiry": null, "status": "unknown", "createdOrder": 2 } ],
  "filter": { "query": "", "location": null, "status": null },
  "expect": { "boxCount": 2, "sections": [ { "category": "pain-fever", "rows": [
      { "name": "DOLIPRANE 1000 mg cp", "boxes": ["b1", "b2"], "status": "unknown",
        "earliestExpiry": { "year": 2027, "month": 3, "day": null } } ] } ] } }
```

Cases cover: product key by GTIN versus by normalized name (no GTIN); the same GTIN in two
categories (two rows); a row name taken from the most recently created box; every pair of
statuses for row status; ordering by urgency, then earliest expiry (month precision sorts as
the last day of its month via `sortKey`), then name, then id; boxes in a row ordered by
expiry with unknown last, then creation order; categories in `categoryOrder`, then unknown
categories by key, `uncategorized` last; empty categories dropped; search on the row name;
location filter (row counts only that location's boxes; a row with none disappears); status
filter (same); filters combined; an empty result; `boxCount` = the boxes shown.

### 2.4 `fixtures/scan-removal.json` (R5)

```json
{ "name": "serial scan with no serial match picks among unserialised boxes, same expiry first",
  "scan": { "gtin": "03400930000014", "serial": "S9", "expiry": { "year": 2027, "month": 3, "day": null } },
  "cabinet": [
    { "id": "b1", "gtin": "03400930000014", "serial": null, "expiry": { "year": 2026, "month": 11, "day": null }, "state": "inCabinet" },
    { "id": "b2", "gtin": "03400930000014", "serial": null, "expiry": { "year": 2027, "month": 3, "day": null }, "state": "inCabinet" },
    { "id": "b3", "gtin": "03400930000014", "serial": "S1", "expiry": null, "state": "inCabinet" } ],
  "expect": { "decision": "choose", "candidates": ["b2", "b1"] } }
```

- `state`: `inCabinet | removed | deleted`. `decision` (cross-platform names): `remove`
  (+ `box`), `choose` (+ ordered `candidates`, ≥ 2), `alreadyRemoved` (+ `box`),
  `notInCabinet`.
- Cases: serial match in the cabinet; serial match removed; serial match deleted (ignored,
  falls through to candidates); serial scan with only differently-serialised boxes
  (`notInCabinet`); no-serial scan matching serialised and unserialised boxes; exactly one
  candidate (`remove`); candidate ordering (same expiry, earlier, later, unknown last, ties
  by creation order); scan without expiry (no "same expiry" bucket); other GTINs ignored;
  empty cabinet.

### 2.5 Schema and repository contract (V)

- `fixtures/schema-v3.sql`: the v3 step only (§4.1), text-compared with each platform's
  copy.
- `fixtures/schema-v3.json`: the full schema after v1 + v2 + v3. `schema-v2.json` stays and
  is checked against a database migrated to v2 only (upgrade test).
- `fixtures/repository-contract.json` gains the checks of §4.2.
- `fixtures/README.md`: new files and names: removal decisions, location-name errors,
  repository errors `duplicateName`, `defaultLocation`, `locationNotEmpty`, preference value
  `scan.removalReason`.
- `scripts/tests/test-shared-fixtures-break.sh`: a broken copy of each new fixture.

## 3. Core (both platforms)

New files in `MediCabinetCore` (`Sources/MediCabinetCore/Cabinet/`) and `:core`
(`com.medicabinet.core.cabinet`), plus additions to `Scan/` / `scan`. Same names on both;
Kotlin uses `Flow` where Swift uses `AsyncThrowingStream`.

### 3.1 Text and location names (S2, L2, L3, L11)

```swift
public enum TextNormalizer {
    public static func normalize(_ text: String) -> String
    /// Every whitespace-separated word of `query` is a substring of `text` (both normalized).
    public static func matches(_ text: String, query: String) -> Bool
}

public enum LocationNameError: Error, Equatable, Sendable {
    case empty, tooLong, duplicateName
    public var kind: String { … }                         // fixture names
}

public enum LocationName {
    public static let maxLength = 50                      // Unicode code points
    /// Trims; checks length and uniqueness against `existing` (excluding `renaming`).
    public static func validate(_ raw: String, existing: [String], renaming current: String? = nil)
        throws(LocationNameError) -> String
}
```

### 3.2 Cabinet rows (C, S, P)

```swift
public struct CabinetFilter: Hashable, Sendable {
    public var query: String = ""
    public var locationID: Location.ID?                  // nil = all
    public var status: ExpiryStatus?                     // nil = all
    public var isActive: Bool { … }
}

public enum ProductKey: Hashable, Sendable { case gtin(String), name(String) }   // name = normalized

public struct ProductRow: Identifiable, Hashable, Sendable {
    public struct ID: Hashable, Sendable { public let category: CategoryID; public let key: ProductKey }
    public let id: ID
    public let name: String                               // most recently created box
    public let boxes: [Box]                               // filtered, P1 order
    public let status: ExpiryStatus                       // most urgent
    public let earliestExpiry: ExpiryDate?
}

public struct CategorySection: Identifiable, Hashable, Sendable {
    public let category: CategoryID
    public let rows: [ProductRow]
    public var id: CategoryID { category }
}

public struct CabinetSnapshot: Hashable, Sendable {
    public let sections: [CategorySection]
    public let boxCount: Int
    public static let empty: CabinetSnapshot
}

public enum ExpiryUrgency {
    /// expired 0, expiringSoon 1, unknown 2, ok 3.
    public static func rank(_ status: ExpiryStatus) -> Int
}

public enum CabinetRows {
    public static func build(
        boxes: [Box],                                     // in the cabinet
        filter: CabinetFilter,
        categoryOrder: [CategoryID],                      // [] until #10
        status: (Box) -> ExpiryStatus
    ) -> CabinetSnapshot
}
```

Algorithm: filter boxes by location and status → group by `(category, productKey)` → keep
groups whose name matches the query → order boxes (expiry `sortKey`, unknown last, then
`createdAt`, then id) → compute row status and earliest expiry → order rows (urgency rank,
earliest `sortKey` or last, normalized name, first box id) → order sections → count.

### 3.3 Removal by scan (R5, R9)

```swift
public enum RemovalDecision: Hashable, Sendable {
    case remove(Box.ID)
    case choose([Box.ID])                                 // ≥ 2, ordered
    case alreadyRemoved(Box.ID)
    case notInCabinet
    public var kind: String { … }                         // fixture names
}

public enum RemovalPolicy {                               // pure, fixture-driven (§2.4)
    /// `serialMatch`: non-deleted box with the scan's GTIN and serial (nil when no serial).
    /// `cabinet`: boxes in the cabinet with the scan's GTIN.
    public static func decide(code: ScannedCode, serialMatch: Box?, cabinet: [Box]) -> RemovalDecision
}

public enum RemovalOutcome: Sendable {
    case removed(Box)                                     // already written
    case choose([Box])
    case alreadyRemoved(Box)
    case notInCabinet
}

public struct ScanRemoval: Sendable {
    public init(boxes: any BoxRepository, clock: any Clock)
    /// Decides, and when the decision is `.remove`, removes with `reason` at `clock.now`.
    public func handle(_ code: ScannedCode, reason: RemovalReason) async throws -> RemovalOutcome
    public func remove(_ id: Box.ID, reason: RemovalReason) async throws -> Box   // after `.choose`
    public func undo(_ id: Box.ID) async throws                                  // undoRemoval
}
```

The fixture suite drives `RemovalPolicy` directly and, in a second pass, `ScanRemoval` on
the in-memory fake (with `state` applied through `insert` / `markRemoved` / `delete`), so
the queries are covered too.

### 3.4 Box edits (B2, B3, B5, B6)

```swift
public enum BoxEditError: Error, Equatable, Sendable { case emptyName, nameTooLong }

public struct BoxEdit: Hashable, Sendable {               // what the edit form can change
    public var name: String
    public var expiry: ExpiryDate?                         // nil only after "Date not printed"
    public var locationID: Location.ID
    public func applied(to box: Box) throws(BoxEditError) -> Box   // trims, 1…200
}
```

Category editing (B4, blocked by #10) adds `category` + `resetCategory()` to `BoxEdit` when
the taxonomy exists. Opened, removal and deletion call the repository directly through the
view model's store (`update` with `isOpened`, `markRemoved` / `undoRemoval`, `delete` /
`undoDelete`).

## 4. Persistence (V)

### 4.1 Migration v3 (identical SQL, `fixtures/schema-v3.sql`)

```sql
ALTER TABLE location ADD COLUMN deletedAt DATETIME;
```

- No index: the location table holds a handful of rows, and every location query reads all
  of them. Box queries already use `box_active`, `box_gtin`, `box_location` and
  `box_serial`.
- The partial unique index `location_single_default` is unchanged: a default location is
  never deleted (V4), so there is always exactly one live default.
- iOS: `Migrations.v3` registered after `v2`. Android: `Migrations.v3` appended
  (`user_version` 3). Bootstrap backs up before migrating and restores on failure (V2); the
  bootstrap tests gain a real v2 → v3 upgrade with data.
- `Location` (core model) is unchanged: deleted locations are never returned. Export (#27)
  reads tombstones through its own query.

### 4.2 Repository port additions

```swift
public enum RepositoryError { …; case duplicateName, defaultLocation, locationNotEmpty }

extension LocationRepository {
    // all(), defaultLocation(): now exclude deleted locations.
    // insert(_:): also throws .duplicateName (normalized, among non-deleted); sortOrder = max + 1 when inserting via `create`.
    func create(named name: String) async throws -> Location               // validated name, placed last
    func rename(id: Location.ID, to name: String) async throws              // .notFound, .duplicateName
    /// `ids` must be exactly the non-deleted locations; sortOrder = index.
    func reorder(_ ids: [Location.ID]) async throws                         // .notFound
    func setDefault(id: Location.ID) async throws                           // .notFound
    func cabinetBoxCount(in id: Location.ID) async throws -> Int
    /// One transaction. `.defaultLocation`; `.notFound`; `.unknownLocation` (destination missing,
    /// deleted or equal to id); `.locationNotEmpty` when boxes are in it and `destination` is nil.
    func delete(id: Location.ID, movingBoxesTo destination: Location.ID?) async throws
    func undoDelete(id: Location.ID) async throws                           // .notFound, .duplicateName
    func observeAll() -> AsyncThrowingStream<[Location], any Error>
}

extension BoxRepository {
    // insert / update: .unknownLocation also for a deleted location.
    func observeCabinet() -> AsyncThrowingStream<[Box], any Error>          // in the cabinet
    func cabinetBoxes(gtin: String) async throws -> [Box]                   // in the cabinet, uses box_gtin
}
```

Every write stamps `updatedAt` from the injected clock (sync-ready); `reorder` stamps only the
locations whose `sortOrder` changed.

New contract checks (names added to `repository-contract.json`, in order):
`deleted locations disappear from all and defaultLocation`,
`create places a location last and rejects a normalized duplicate name`,
`rename rejects a duplicate name and allows a case change of its own name`,
`reorder sets sortOrder and rejects an incomplete id list`,
`setDefault leaves exactly one default`,
`the default location cannot be deleted`,
`deleting a location with cabinet boxes needs a destination`,
`delete moving boxes moves only boxes in the cabinet and tombstones the location`,
`undoDelete restores an empty deleted location unless its name was taken`,
`boxes cannot be inserted into or moved to a deleted location`,
`observeCabinet emits after each write and excludes removed and deleted boxes`,
`observeAll emits after location changes`,
`cabinetBoxes returns boxes in the cabinet with a gtin only`.

## 5. iOS app

```
ios/MediCabinet/Sources/Features/Cabinet/
  CabinetView.swift             # tab root: NavigationStack, sections, .searchable, filter bar, states
  CabinetViewModel.swift        # @MainActor @Observable; observes boxes + locations; builds snapshot off-main
  CabinetFilterBar.swift        # location Menu, status Menu, active filters + Clear
  ProductBoxesView.swift        # P1
  BoxDetailView.swift           # B1, B3, B5, B6
  BoxDetailViewModel.swift
  BoxEditView.swift             # B2 (ExpiryField from #12, location Picker)
  ExpiryStatusLabel.swift       # text + SF Symbol per status (reused by #24)
ios/MediCabinet/Sources/Features/Common/
  UndoBanner.swift              # §1.7 (+ UndoCenter: @MainActor @Observable, one pending action)
ios/MediCabinet/Sources/Features/Settings/Locations/
  LocationsView.swift           # List + .onMove + EditButton; accessibility actions move up/down
  LocationsViewModel.swift
  LocationNameSheet.swift       # create / rename, inline validation message
  DeleteLocationSheet.swift     # L8: count, destination Picker, "Move and delete" (destructive role)
ios/MediCabinet/Sources/Features/Scan/        (from #12, extended)
  ScanMode.swift                # enum add / remove
  RemovalReasonChip.swift       # R3
  RemovalCandidatesSheet.swift  # R7
```

- `CabinetViewModel`: `filter` (search debounced 150 ms), `snapshot`, `state` (`loading`,
  `loaded`, `failed`). The `boxes` and `locations` streams and a `today` tick feed a
  `Task.detached` that runs `CabinetRows.build`; the latest result wins (the previous task is
  cancelled).
- Navigation: `NavigationStack(path:)` with `ProductRow.ID` and `Box.ID` destinations; a row
  with one box pushes `Box.ID` (P2). When the shown box leaves the cabinet, the detail pops
  (P3).
- Empty-cabinet button: `MainTabView` gets a `@State selection` and passes a
  `openScan` closure.
- `AppEnvironment` gains `expiryStatus: @Sendable (ExpiryDate?, Date) -> ExpiryStatus` (bound
  to #23), `categoryOrder: [CategoryID]` (bound to #10, `[]` until then), `undo: UndoCenter`,
  `scanRemoval: ScanRemoval`.
- Removal mode in `ScanViewModel`: `mode: ScanMode` (`.add` on every `onAppear`),
  `removalReason`. Recognitions in `.remove` go to `ScanRemoval.handle`. The session list
  shows removals with per-row Undo; `ScanSummaryView` shows removal counts in remove mode.
- Mode switch: segmented `Picker` in the scanner overlay. Remove mode tints the overlay and
  shows the title "Retirer" with `minus.circle`; announcement on change.
- Locations: Settings gets a "Lieux" / "Locations" row above About.

## 6. Android app

```
android/app/src/main/kotlin/com/medicabinet/app/features/cabinet/
  CabinetScreen.kt  CabinetViewModel.kt  CabinetFilterBar.kt  ProductBoxesScreen.kt
  BoxDetailScreen.kt  BoxDetailViewModel.kt  BoxEditSheet.kt  ExpiryStatusLabel.kt
android/app/src/main/kotlin/com/medicabinet/app/features/common/
  UndoSnackbar.kt               # UndoController: one pending action → SnackbarHostState
android/app/src/main/kotlin/com/medicabinet/app/features/settings/locations/
  LocationsScreen.kt  LocationsViewModel.kt  LocationNameDialog.kt  DeleteLocationDialog.kt
  ReorderableColumn.kt          # long-press drag inside LazyColumn (no third-party library)
android/app/src/main/kotlin/com/medicabinet/app/features/scan/   (from #12, extended)
  ScanMode.kt  RemovalReasonChip.kt  RemovalCandidatesSheet.kt
```

- `CabinetViewModel`: `combine(boxes, locations, filter.debounce(150), statusInputs)`
  `.mapLatest { CabinetRows.build(…) }.flowOn(default)` → `StateFlow<CabinetUiState>`
  (`Loading`, `Loaded(snapshot, filter, locations)`, `Failed`). Injected dispatchers.
  `statusInputs` = today (from `Clock`, re-emitted on `ON_START` and at midnight) + the #23
  window.
- Navigation routes: `cabinet/home`, `cabinet/product/{category}/{key}`, `cabinet/box/{id}`,
  `settings/locations`. The product key is URL-encoded and never logged.
- Search: Material 3 `SearchBar` (docked) at the top of the list; filters as `FilterChip`s
  with `DropdownMenu`s.
- Reorder: `ReorderableColumn` uses `detectDragGesturesAfterLongPress` on a drag handle
  (48 dp) and swaps items as the pointer crosses item bounds. Each row also carries
  `CustomAccessibilityAction`s "Monter" / "Descendre" (L4). The order is saved on drop.
- `MainScaffold` owns the `SnackbarHostState`; `AppEnvironment` gains `expiryStatus`,
  `categoryOrder`, `scanRemoval`.
- Mode switch: `SingleChoiceSegmentedButtonRow`; remove mode as on iOS; `LiveRegionMode.Polite`
  announcement.
- Architecture rule (`android-check-architecture.py`): `features/cabinet` and
  `features/settings/locations` import no `persistence` package (views → view models →
  repository ports only), plus a fixture.

## 7. Flows

1. **Open the Cabinet tab**: `loading` → first emission of `observeCabinet` + `observeAll`
   → `build` off the main thread → sections shown. Later writes (scan on another tab, import)
   re-emit while visible.
2. **Search "doli 1000"**: filter changes → debounce 150 ms → `build` → rows; the header
   count updates; "no results" with **Clear** when empty.
3. **Used up from box detail**: `markRemoved(id, usedUp, now)` → the observation re-emits →
   detail pops → `UndoBanner` / Snackbar "Doliprane — utilisé" **Undo** → `undoRemoval(id)`.
4. **Added by mistake**: `delete(id)` → same, **Undo** → `undoDelete(id)`;
   `.duplicateSerial` → message "Impossible de restaurer : une autre boîte a ce numéro".
5. **Delete "Voiture" holding 4 boxes**: `cabinetBoxCount` = 4 → `DeleteLocationSheet`
   (destination = default) → `delete(id, movingBoxesTo:)` (one transaction) → list
   re-emits. An empty location → `delete(id, nil)` → **Undo** → `undoDelete`.
6. **Remove by scan, serial match**: frame → debouncer → `parse` → `ScanRemoval.handle`
   (`box(gtin:serial:)` → `RemovalPolicy` → `markRemoved`) → `.removed(box)` → removal
   haptic, card with **Undo**, session list +1. Target < 300 ms.
7. **Remove by scan, several candidates**: `.choose(boxes)` → camera ignores codes →
   `RemovalCandidatesSheet` (first pre-selected) → **Remove** → `ScanRemoval.remove(id,
   reason)` → card; **Cancel** → nothing.
8. **Remove by scan, not in the cabinet**: `.notInCabinet` → "not added" haptic, "Pas dans
   l'armoire", camera continues.

## 8. Performance

- `CabinetRows.build` on 1 000 boxes (realistic mix: 300 products, 8 categories, 4 locations,
  every status) with a query and a location filter: **≤ 100 ms** median of 10 runs, in a
  core test on both platforms (expected < 10 ms on a desktop).
- Observation re-query of 1 000 boxes: ≤ 50 ms median in an iOS app test (GRDB temp file)
  and an Android Robolectric test.
- Remove by scan: `handle` (one indexed lookup + one update) on a 1 000-box cabinet
  **≤ 100 ms** median, same harness as bulk-scan §8, guarding the < 300 ms target.
- Lists: stable ids (`ProductRow.ID`, `Box.ID`); iOS `List` / Android `LazyColumn` with
  `key`s; no per-row queries (rows carry their boxes). Scrolling 1 000 boxes is checked once
  on the oldest available device in the device task (recorded in
  `reports/cabinet-inventory/README.md`).
- No I/O or `build` on the main thread; the camera rules of #12 are unchanged.

## 9. Privacy impact

| Item | iOS | Android |
|------|-----|---------|
| New permission | none | none |
| Privacy manifest | unchanged (UserDefaults already declared by #12) | — |
| Data collected / sent | none | none |
| Stored | `location.deletedAt`; preference `scan.removalReason` | same |
| Logging | failure kinds only (`RepositoryError.kind`, `LocationNameError.kind`); never names, search text, product keys, lots or serials | `AppLog` failure kinds only |
| Navigation | — | product key in a route argument stays in memory; never logged |

## 10. Test strategy

| Req | Test (iOS / Android) | Kind |
|-----|----------------------|------|
| S1, S2, C4 (names) | `TextNormalizerFixtureTests` / `TextNormalizerFixtureTest` | core, shared |
| L2, L3, L11 | `LocationNameFixtureTests` / `LocationNameFixtureTest` | core, shared |
| C1–C5, S3, S4, P1 | `CabinetRowsFixtureTests` / `CabinetRowsFixtureTest` + perf test | core, shared |
| R5 | `RemovalPolicyFixtureTests` + `ScanRemovalTests` (fake) / Kotlin twins | core, shared |
| B2 | `BoxEditTests` (trim, 1…200, unknown expiry kept, never pre-filled) | core |
| V1–V4, L5–L10 | `SchemaFixtureTests` (v3), `MigrationsTests` (v2 → v3 with data), `RepositoryContract` (new checks) on fake + GRDB / SQL, bootstrap upgrade test | integration |
| C6 | contract `observeCabinet emits…`; view-model test: a write elsewhere updates the snapshot | integration + unit |
| C7–C10, S5–S7 | `CabinetViewModelTests` / `CabinetViewModelTest` (fake repos, fixed clock): loading, failure + Retry, empty, no results + Clear, counts, filters cleared on deleted location (L10) | unit |
| C1–C3, S, P2, P3 | UI: `CabinetUITests` (seeded cabinet via launch environment) / Robolectric `CabinetScreenTest` | UI |
| B1–B3, B5–B7 | `BoxDetailViewModelTests` + UI tests: edit/save/cancel, opened toggle, used up → Undo, mistake → Undo, duplicateSerial message | unit + UI |
| L1–L8 | `LocationsViewModelTests` + UI tests: create, rename error, reorder (incl. accessibility actions), set default, delete empty → Undo, delete with boxes → move, default not deletable | unit + UI |
| R1–R4, R6–R9 | `ScanViewModelTests` (fake scanner): mode resets to Add, reason remembered, each outcome's feedback, candidates sheet blocks recognitions, session list Undo, summary; UI: injected payloads in remove mode | unit + UI |
| R6 | perf test (§8) | perf |
| X1 | log-policy lint (existing), `PrivacyTest` unchanged (still exactly CAMERA) | gate |
| X3 | UI tests at the largest accessibility text size / font scale 2.0 on Cabinet, detail, Locations; labels on every control; status text present | UI |
| X4 | `ios-check-strings.sh`, `check-string-parity.py` | gate |
| B4 | `BoxEditTests` category cases + UI — **with #10** | core + UI |

Each feature has a success, a failure and an edge-case test on both platforms; every
fixture-driven suite also asserts its file loads and is non-empty.

## 11. i18n impact

New keys on both platforms (fr = source, en), same wording. Main groups:
`cabinet.{title,count}` (plural), `cabinet.search.prompt`,
`cabinet.filter.{location,allLocations,status,all,clear}`,
`cabinet.empty.{title,message,scan}`, `cabinet.noResults.{title,clear}`,
`cabinet.loading`, `cabinet.error.{title,retry}`, `cabinet.row.{boxes}` (plural),
`expiry.status.{expired,expiringSoon,ok,unknown}` (shared with #23/#24 if they define them
first: one key, one owner), `category.uncategorized`,
`box.{code,noCode,category,location,expiry,lot,added,opened}`,
`box.edit.{title,name,save,cancel,nameEmpty,nameTooLong}`,
`box.remove.{usedUp,thrownAway,mistake,mistakeExplanation}`,
`undo.{action,usedUp,thrownAway,deleted,locationDeleted,restoreFailed}`,
`locations.{title,add,rename,default,makeDefault,delete,defaultNotDeletable}`,
`locations.name.{placeholder,empty,tooLong,duplicate}`,
`locations.delete.{title,message,destination,confirm,cancel}` (plural),
`locations.move.{up,down}`, `scan.mode.{add,remove,announcement}`,
`scan.removal.{reason,usedUp,thrownAway,removed,alreadyRemoved,notInCabinet}`,
`scan.removal.choose.{title,remove,cancel}`, `scan.removal.summary` (plural).
Dates: `Date.FormatStyle` / `DateTimeFormatter` (month-year for month precision).

## 12. Risks

- **Dependencies**: #12 must merge first; #23's status rule (and its keys) must exist before
  C3/S4 ship; #10 gates B4. If #23 is late, the Cabinet list cannot ship: status is part of
  C3, and "unknown never shown as OK" forbids a placeholder.
- **Compose drag-to-reorder** is custom code (no stable AndroidX component): kept small,
  behind accessibility actions that work without dragging, with a Robolectric test of the
  swap logic.
- **Normalization drift** between Foundation and `java.text.Normalizer` (Unicode versions):
  the fixture pins the cases that matter; an exotic difference only affects search on that
  exotic text.
- **Observation churn** during an import (#29) of 1 000 boxes: one transaction gives one
  emission on both platforms (GRDB coalesces; the Android counter is bumped once per
  transaction).
- **Undo window**: a 10 s banner can be missed; the action is still reversible by
  re-scanning (*Put back*, #12) for removals. Deleted boxes are tombstones and an export
  carries them, but no UI restores them after the banner. Accepted (R/B7 decision).
