# Foundation — Design

Status: **Approved 2026-10-19**
Epic: #1 · Requirements: [`requirements.md`](requirements.md)

## 1. Architecture

```
┌──────────────────────── MediCabinet (app target) ────────────────────────┐
│  App/            MediCabinetApp, AppEnvironment (manual DI), Bootstrap    │
│  Features/       Scan/ Cabinet/ Expiring/ Settings/  (SwiftUI views + VMs)│
│  Persistence/    AppDatabase (GRDB), Migrations, Records, Repositories,  │
│                  BackupService                                            │
│  Resources/      Localizable.xcstrings, InfoPlist.xcstrings, Assets,      │
│                  PrivacyInfo.xcprivacy                                    │
└───────────────┬──────────────────────────────────────────────────────────┘
                │ depends on (protocols + value types only)
┌───────────────▼──────────── Packages/MediCabinetCore ─────────────────────┐
│  Model/   Box, Location, ProductRef, CategoryID, ExpiryDate,              │
│           ExpiryStatus, RemovalReason                                     │
│  Ports/   LocationRepository, BoxRepository (protocols), Clock            │
│  (later epics: GS1/, Classification/, Expiry/ rules, Archive/)            │
└───────────────────────────────────────────────────────────────────────────┘
```

- **Layering:** View → ViewModel (`@Observable`, `@MainActor`) → repository protocol (core)
  → GRDB implementation (app `Persistence/`). Core never imports GRDB; GRDB record types map
  to/from core value types.
- **DI:** no framework. `AppEnvironment` (a struct holding the repositories, clock, bundle
  info) is built once in `Bootstrap` and injected with SwiftUI `.environment`. Tests build
  their own environment with in-memory databases and a `FixedClock`.
- **Concurrency:** Swift 6 language mode, complete strict concurrency. Core types are
  `Sendable` value types. Repositories are `Sendable` and `async`; GRDB `DatabasePool`
  async APIs keep I/O off the main actor (R4.6).

## 2. Project generation (R1)

`project.yml` (XcodeGen):

| Setting | Value |
|---------|-------|
| `name` | `MediCabinet` |
| `options.deploymentTarget.iOS` | `17.0` |
| `TARGETED_DEVICE_FAMILY` | `1` (iPhone) |
| `SWIFT_VERSION` | `6.0`, `SWIFT_STRICT_CONCURRENCY = complete` |
| `PRODUCT_BUNDLE_IDENTIFIER` | `com.medicabinet.app` |
| `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` | `0.1.0` / `1` |
| `configFiles` | Debug/Release → `Config/Signing.xcconfig` (which `#include?`s `Signing.local.xcconfig`) |
| `packages` | `MediCabinetCore` (local path `Packages/MediCabinetCore`), `GRDB` (remote, `exactVersion` pinned to the latest 7.x at implementation time) |
| Targets | `MediCabinet` (app), `MediCabinetTests` (unit, Swift Testing), `MediCabinetUITests` (XCUITest) |
| `LOCALIZATION_PREFERS_STRING_CATALOGS` | `YES`, development region `fr`, known regions `fr en` |

Same pattern as Riding (`ios/project.yml`, `Config/Signing.xcconfig`,
`Signing.local.xcconfig.sample`) so the user's existing workflow applies.

`Packages/MediCabinetCore/Package.swift`: `swift-tools-version: 6.0`, platforms
`.iOS(.v17), .macOS(.v14)`, one library target, one test target, no dependencies.

## 3. Components and interfaces

### 3.1 Core model (R3)

```swift
public struct Location: Identifiable, Hashable, Sendable {
    public let id: UUID
    public var name: String
    public var isDefault: Bool
    public var sortOrder: Int
    public let createdAt: Date
    public var updatedAt: Date
}

public struct CategoryID: RawRepresentable, Hashable, Sendable, Codable {
    public let rawValue: String          // stable key, e.g. "pain-fever"; taxonomy in #10
    public static let uncategorized = CategoryID(rawValue: "uncategorized")
}

public struct ProductRef: Hashable, Sendable {
    public var gtin: String?             // 14 digits when known (CIP13 → GTIN-14 in #11)
    public var name: String              // snapshot, so a box stays readable if reference data changes
}

public struct ExpiryDate: Hashable, Sendable, Comparable {
    public let year: Int, month: Int
    public let day: Int?                  // nil = month precision (GS1 DD=00 → end of month)
    public static let supportedYears = 2000...2099
    public static func make(year: Int, month: Int, day: Int?) throws(ExpiryDateError) -> ExpiryDate  // only way to build one
    public var isMonthPrecision: Bool
    public var lastValidDay: Int          // printed day, or last day of the month (leap-year aware)
    public var sortKey: String            // "YYYY-MM-DD" of lastValidDay; also the DB `expirySortKey`
}

public enum ExpiryDateError: Error, Equatable { case invalidYear(Int), invalidMonth(Int), invalidDay(Int, month: Int, year: Int) }

public enum ExpiryStatus: Sendable { case expired, expiringSoon, ok, unknown }   // rules in #23

public enum RemovalReason: String, Sendable, Codable { case usedUp, thrownAway }

public struct Box: Identifiable, Hashable, Sendable {
    public let id: UUID
    public var product: ProductRef
    public var category: CategoryID
    public var categoryOverridden: Bool
    public var locationID: Location.ID
    public var expiry: ExpiryDate?        // nil = unknown, never defaulted (R3.6)
    public var lot: String?
    public var serial: String?
    public var isOpened: Bool
    public let createdAt: Date
    public var updatedAt: Date
    public var removal: Removal?          // nil = in the cabinet (R4.7)
    public struct Removal: Hashable, Sendable { public var reason: RemovalReason; public var date: Date }
}

public protocol Clock: Sendable { var now: Date { get } }
public struct SystemClock: Clock { … }
public struct FixedClock: Clock { … }   // in core so app tests can use it too
```

`ExpiryDate` is a struct with a private initializer (refined from the first draft's enum with public cases, which let callers bypass validation). Year validity: 2000…2099 (GS1 `YY` maps to 20YY; manual entry is bounded the
same way). Anything else → `invalidYear`.

### 3.2 Repository ports (core)

Repositories are created with an injected `Clock` and stamp `updatedAt` themselves; callers
never set it. Errors are `RepositoryError` (`notFound`, `unknownLocation`, `duplicate`,
`duplicateDefault`).

```swift
public protocol LocationRepository: Sendable {
    func all() async throws -> [Location]                       // sortOrder, then name
    func defaultLocation() async throws -> Location?
    func insert(_ location: Location) async throws
    func ensureDefault(named name: String) async throws -> Location   // R6, idempotent
}

public protocol BoxRepository: Sendable {
    func activeBoxes() async throws -> [Box]          // removal == nil
    func box(id: Box.ID) async throws -> Box?         // includes removed boxes
    func insert(_ box: Box) async throws
    func update(_ box: Box) async throws              // keeps createdAt and removal
    func markRemoved(id: Box.ID, reason: RemovalReason, at date: Date) async throws
    func undoRemoval(id: Box.ID) async throws
}
```

The package also exposes a `MediCabinetCoreTestSupport` library: `MutableClock`, the
`InMemoryCabinetStore` fake and `RepositoryContract` (a framework-agnostic list of checks every
implementation must pass — the GRDB repositories run the same list in T10).

Only what the foundation needs and tests; later epics extend the protocols in their own spec.

### 3.3 Persistence (app, R4–R5)

- `AppDatabase` wraps a GRDB `DatabasePool` at
  `Application Support/MediCabinet/cabinet.sqlite`; `AppDatabase.inMemory()` /
  `AppDatabase.temporary()` for tests.
- File protection: `FileProtectionType.completeUntilFirstUserAuthentication` on the
  directory and DB files. Rationale: notification digests (#25) may be rescheduled when the
  app is woken while the phone is locked; `complete` would make the DB unreadable then. Data
  is still encrypted at rest whenever the phone has not been unlocked since boot.
- `GRDBLocationRepository`, `GRDBBoxRepository` implement the core ports; `LocationRecord`,
  `BoxRecord` (`FetchableRecord & PersistableRecord`) map to core types.
- `BackupService`:
  ```swift
  struct BackupService {
      func backupIfNeeded(database: DatabasePool, migrator: DatabaseMigrator) throws -> URL?  // nil when fresh DB or nothing pending
      func prune(keeping count: Int = 5) throws
      // later: restore(from:) for import (#29)
  }
  ```
  Uses GRDB's `backup(to:)` (online SQLite backup API) into
  `Application Support/MediCabinet/Backups/pre-migration-<UTC yyyyMMdd-HHmmss>-<fromMigration>.sqlite`.
  Pruning sorts by the timestamp in the file name (no file-date API → no required-reason API).
- **Device backups:** `Application Support` is included in the user's iCloud / Finder iPhone
  backup by default, and we keep it that way (no `isExcludedFromBackup`), so the cabinet and
  its pre-migration copies survive a phone restore even without a manual export.

### 3.4 Bootstrap (R4.1, R4.3, R5, R6)

```
App launch
1. Bootstrap.start() on a background task; UI shows a launch placeholder.
2. Open DatabasePool (create directory + file protection if new).
3. isNew = migrator.appliedIdentifiers(db).isEmpty && no tables
4. If !isNew and migrator.hasCompletedMigrations(db) == false:
     backupURL = BackupService.backupIfNeeded(...)       ── failure → .failed(.backup) (R5.2)
5. try migrator.migrate(pool)                             ── failure → restore backupURL if any,
                                                              → .failed(.migration) (R4.3)
6. LocationRepository.ensureDefault(named: String(localized: "location.default.name"))  (R6)
7. BackupService.prune(keeping: 5)                         ── failure logged only
8. State → .ready(AppEnvironment) → TabView shown
```

Failure kinds: `.storage` (database cannot be opened or written), `.backup` (R5.2), `.migration` (R4.3). `.failed` shows a localized blocking screen with **Retry** (re-runs from step 2). It never
deletes the database. GRDB runs each migration in its own transaction, so a failure in
migration *n* can leave *n-1* applied — hence step 5 restores the pre-migration backup to
return to exactly the pre-launch state.

Restore procedure: close the `DatabasePool` first, then replace `cabinet.sqlite` with the
backup copy and delete the `-wal` / `-shm` side files, then reopen (Retry). On a **first
launch** there is no backup and nothing to lose: a failed `v1` leaves the empty file in place
and Retry migrates it again.

`updatedAt` is set by the repositories on every write from the injected `Clock` — callers
never set it.

### 3.5 App shell (R2)

- `RootView`: switches on bootstrap state (loading / failed / ready).
- `MainTabView`: `TabView` with `ScanPlaceholderView`, `CabinetPlaceholderView`,
  `ExpiringPlaceholderView`, `SettingsView`. SF Symbols: `barcode.viewfinder`,
  `cross.case`, `calendar.badge.exclamationmark`, `gearshape`.
- Placeholders use `ContentUnavailableView` (iOS 17) with localized title/description.
- `SettingsView` → `AboutView`: name, `version (build)` read via an injected `AppInfo`
  (wrapping `Bundle.main` so tests can fake it), not-a-medical-device statement.
- Accessibility identifiers on tabs and About labels for UI tests.

## 4. Data model — migration `v1`

```sql
CREATE TABLE location (
  id          TEXT PRIMARY KEY NOT NULL,        -- UUID string
  name        TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
  isDefault   INTEGER NOT NULL DEFAULT 0,
  sortOrder   INTEGER NOT NULL DEFAULT 0,
  createdAt   DATETIME NOT NULL,
  updatedAt   DATETIME NOT NULL
);
CREATE UNIQUE INDEX location_single_default ON location(isDefault) WHERE isDefault = 1;  -- R6.2

CREATE TABLE box (
  id                 TEXT PRIMARY KEY NOT NULL,
  gtin               TEXT CHECK (gtin IS NULL OR length(gtin) = 14),
  productName        TEXT NOT NULL CHECK (length(productName) BETWEEN 1 AND 200),
  category           TEXT NOT NULL DEFAULT 'uncategorized',
  categoryOverridden INTEGER NOT NULL DEFAULT 0,
  locationId         TEXT NOT NULL REFERENCES location(id) ON DELETE RESTRICT,
  expiryYear         INTEGER,
  expiryMonth        INTEGER CHECK (expiryMonth IS NULL OR expiryMonth BETWEEN 1 AND 12),
  expiryDay          INTEGER CHECK (expiryDay IS NULL OR expiryDay BETWEEN 1 AND 31),  -- NULL + month set = month precision
  expirySortKey      TEXT,                     -- 'YYYY-MM-DD' of last valid day, for ordering/index; NULL = unknown
  lot                TEXT CHECK (lot IS NULL OR length(lot) <= 20),
  serial             TEXT CHECK (serial IS NULL OR length(serial) <= 20),
  isOpened           INTEGER NOT NULL DEFAULT 0,
  removalReason      TEXT CHECK (removalReason IS NULL OR removalReason IN ('usedUp','thrownAway')),
  removedAt          DATETIME,
  createdAt          DATETIME NOT NULL,
  updatedAt          DATETIME NOT NULL,
  CHECK ((expiryYear IS NULL) = (expiryMonth IS NULL)),
  CHECK ((expiryYear IS NULL) = (expirySortKey IS NULL)),
  CHECK (expiryDay IS NULL OR expiryYear IS NOT NULL),
  CHECK ((removalReason IS NULL) = (removedAt IS NULL))
);
CREATE INDEX box_gtin       ON box(gtin);
CREATE INDEX box_location   ON box(locationId);
CREATE INDEX box_expiry     ON box(expirySortKey);
CREATE INDEX box_active     ON box(removedAt);
CREATE INDEX box_serial     ON box(gtin, serial) WHERE serial IS NOT NULL;   -- duplicate check in #15
```

Lengths follow GS1 limits (AI `10` and `21`: up to 20 characters). The `box_serial` index is
deliberately **not unique** in v1; duplicate policy belongs to #15. Removed boxes stay in the
table (R4.7). No `UserDefaults` keys are introduced in this epic.

## 5. Tooling (R7)

| Script | Does |
|--------|------|
| `scripts/local-ci.sh` | `xcodegen generate` → `scripts/lint.sh` → `swift test --enable-code-coverage` (core) → `xcodebuild test -enableCodeCoverage YES -resultBundlePath build/ci.xcresult` (scheme `MediCabinet`, first available iPhone simulator, same selection as Riding) → `scripts/coverage-gate.sh` → `scripts/check-strings.sh` → `scripts/check-version.sh`. `set -euo pipefail`, prints `▶ step` / `✗ step failed`. |
| `scripts/lint.sh` | `swiftlint lint --strict --quiet --baseline .swiftlint-baseline.json`; `--update-baseline` only after fixing (as in Riding). |
| `scripts/coverage-gate.sh` | Core: `xcrun llvm-cov export -summary-only` on the SwiftPM profdata → line % of `Sources/`. App: `xcrun xccov view --report --json build/ci.xcresult` → line % of `MediCabinet.app`. Fails < 80 (R7.3). Threshold in one variable. |
| `scripts/check-strings.sh` | Python 3 (`python3 -I`, stdlib only) reads every `*.xcstrings`; fails when a key lacks a `translated` value for `fr` or `en` (R8.2). |
| `scripts/check-version.sh` | Compares `MARKETING_VERSION` in `project.yml` with the first `## X.Y.Z` heading of `CHANGELOG.md` (R7.4). |

`.swiftlint.yml`: default rules + `force_unwrapping`, `force_try` (opt-in, excluded in
tests), `explicit_acl` off, line length 140; excludes `.build`, `build`, generated project.
Baseline starts empty.

## 6. Privacy (R9)

`PrivacyInfo.xcprivacy`: `NSPrivacyTracking = false`, no tracking domains, no collected data
types, required-reason APIs: none expected in this epic (no `UserDefaults`, no file
timestamps, no disk-space or boot-time APIs). Each later epic updates it when it adds such an
API. Logging via `Logger(subsystem: "com.medicabinet.app", category: …)`; any box/location
value interpolated with `privacy: .private`.

## 7. Sequence — key scenarios

1. **First launch:** no DB → create → `isNew` → migrate v1 (no backup, R5.4) → create default
   location (R6.1) → tabs.
2. **Upgrade with a pending migration:** DB exists, pending `v2` → backup file written →
   migrate → prune to 5 → tabs.
3. **Backup cannot be written** (e.g. disk full): no migration → blocking error with Retry
   (R5.2); DB untouched.
4. **Migration fails:** restore pre-migration backup → blocking error with Retry (R4.3).

## 8. Performance impact

| Target | How it is met / verified |
|--------|--------------------------|
| Cold start < 1 s | Bootstrap is a handful of SQLite statements on an empty DB; measured with `XCTApplicationLaunchMetric` in a UI test, result noted in `reports/foundation/README.md` (measured, not gated — simulator timing is noisy). |
| No I/O on main actor | Repositories are `async` over `DatabasePool`; a unit test asserts bootstrap runs off the main actor. |
| Indexed lookups | Indexes in §4; `EXPLAIN QUERY PLAN` test on `activeBoxes()` and GTIN lookup asserts index use. |

The scan path, reference data and import/export do not exist yet.

## 9. Test strategy

| Requirement | Test | Kind |
|-------------|------|------|
| R1.1–R1.4 | `local-ci.sh` from a clean clone generates and builds; `.gitignore` excludes `*.xcodeproj` | CI |
| R2.1, R2.2 | `MainTabUITests`: 4 tabs exist with labels; each placeholder shows its title | UI |
| R2.3 | `AboutUITests`: version/build text equals bundle values; `AboutViewModelTests` with fake `AppInfo` | UI + unit |
| R2.5 | Lint custom rule: no `URLSession`, `import Network` or `URLRequest` in app or core sources (later epics that add a download must amend this rule in their spec) | lint |
| R2.4 | UI test launched with `-UIPreferredContentSizeCategoryName UICTContentSizeCategoryAccessibilityXXXL` and dark mode: tabs still hittable | UI |
| R3.1 | `swift test` in CI; core has no forbidden imports (`scripts/lint.sh` custom rule) | CI |
| R3.4, R3.5 | `ExpiryDateTests`: day/month precision, Feb 28/29 (2028 leap, 2100 excluded by year bound), month 0/13, day 0/32, Apr 31, year bounds | unit |
| R3.6 | `BoxTests`: `expiry == nil` round-trips; no API returns `.ok` for nil (only types now — asserted again in #23) | unit |
| R3.7 | `FixedClock` used in all time-dependent tests; lint custom rule forbids `Date()` in core `Sources/` | unit + lint |
| R4.1, R4.2 | `MigrationTests`: fresh temp DB → tables, columns, indexes exist; constraints reject invalid rows (bad month, lone removal date, second default) | integration |
| R4.3 | `BootstrapTests`: injected failing migration → state `.failed(.migration)`, DB identical to before (checksum), backup restored | integration |
| R4.4 | `AppDatabaseTests`: a recording `FileManager` proves the directory and DB files are given `completeUntilFirstUserAuthentication` (the simulator does not expose Data Protection — the attribute reads back nil); a read-back test runs on a real device only (`#if !targetEnvironment(simulator)`) | integration |
| R4.5 | Lint custom rule: `import GRDB` only allowed under `Persistence/` | lint |
| R4.6 | `BootstrapTests`: work asserted off main actor | unit |
| R4.7 | `GRDBBoxRepositoryTests`: markRemoved hides from `activeBoxes()`, `undoRemoval` restores | integration |
| R5.1–R5.4 | `BackupServiceTests`: pending migration → backup exists and opens; fresh DB → none; unwritable folder → error and no migration; 7 backups → 5 kept, newest retained | integration |
| R6.1, R6.2 | `GRDBLocationRepositoryTests`: `ensureDefault` creates once, idempotent on second launch; DB rejects two defaults | integration |
| R7.2–R7.5 | Run each check script against a deliberately broken fixture (`scripts/tests/`) and assert non-zero exit | script |
| R8.1, R8.2 | `check-strings.sh` in CI; fixture catalog with a missing `en` value fails | script |
| R8.3 | `AboutViewModelTests` formats with `fr_FR` and `en_US` locales | unit |
| R9.1–R9.3 | Review checklist in PR + plist presence test (`Bundle.main.url(forResource: "PrivacyInfo", …)`) | unit |
| R10 | `check-version.sh` | script |

Each component includes at least one success, one failure and one edge case (see rows).

## 10. i18n impact

New keys (fr / en), all in `Localizable.xcstrings`:
`tab.scan`, `tab.cabinet`, `tab.expiring`, `tab.settings`, `placeholder.scan.title|message`,
`placeholder.cabinet.title|message`, `placeholder.expiring.title|message`,
`settings.title`, `settings.about`, `about.version %@ (%@)`, `about.disclaimer`,
`location.default.name`, `bootstrap.loading`, `bootstrap.error.backup.title|message`,
`bootstrap.error.migration.title|message`, `bootstrap.retry`.
`InfoPlist.xcstrings`: `CFBundleDisplayName` ("MediCabinet").

## 11. Risks

- **GRDB + Swift 6 strict concurrency:** GRDB 7 supports Swift 6; pin an exact version and
  fix warnings at integration time.
- **Simulator coverage noise:** UI tests contribute to app coverage; if flaky, the gate uses
  unit-test coverage only — would be recorded as a spec change, not done silently.
- **Lint custom rules** (forbidden imports / `Date()`) are regex-based; documented
  limitations in `docs/development.md`.
