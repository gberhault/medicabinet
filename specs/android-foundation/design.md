# Android foundation — Design

Status: **Approved 2026-10-19**
Epic: #31 · Requirements: [`requirements.md`](requirements.md)

The iOS foundation design ([`../foundation/design.md`](../foundation/design.md)) is the
reference: this document only states how each part is done on Android, what changes in the
repository, and where the two platforms are tied together by shared fixtures.

## 1. Key decision — plain SQLite (`androidx.sqlite`), the exact iOS DDL

*Revised 2026-10-19 during implementation (the first version of this design chose SQLDelight).*

Requirement P1 asks for the **same invariants as iOS v1**. iOS enforces them in SQL (CHECK
constraints, a partial unique index for the single default location, `ON DELETE RESTRICT`).
**Room** cannot express these: entity annotations have no CHECK support, `@Index` has no `WHERE`
clause, and Room's open-time schema validation rejects indexes it did not create itself.

**SQLDelight** was chosen first and dropped in T14: its SQLite dialect rejects the `DATETIME`
type name iOS v1 uses (Android would have needed `TEXT` plus a fixture alias), its Gradle plugin
conflicts with AGP 9 on the root classpath, and its `AS Boolean`/`AS Int` columns need adapters —
most of its benefit gone, workarounds left.

**Decision (user, 2026-10-19): plain SQLite through `androidx.sqlite`** (`sqlite-framework`
2.7.1, Apache-2.0): `SupportSQLiteOpenHelper` on the platform SQLite (no bundled native library).

- The v1 schema is **the iOS v1 SQL, character for character** (`Migrations.kt`), so
  `fixtures/schema-v1.json` matches both platforms exactly, with no alias.
- Migrations are an ordered list of SQL steps; the schema version is `PRAGMA user_version`
  (iOS tracks applied migrations in GRDB's table; each platform uses its native mechanism).
- Hand-written SQL in the repositories, the same style as GRDB on iOS. A SQL typo shows up at
  run time, not compile time — the repository contract and schema tests cover every statement.
- The same SQL can run unchanged on a future Raspberry Pi sync service (see CLAUDE.md, Product
  decisions → Sync).

Issue #36 is titled "Plain SQLite persistence, pre-migration backup and bootstrap".

## 2. Repository after the restructure (M)

```
CLAUDE.md  AGENTS.md  README.md  .gitignore
fixtures/                      # shared, language-neutral test vectors (JSON)
  README.md
  expiry-dates.json
  schema-v1.json               # tables, columns, indexes every platform's v1 must have
ios/                           # moved as-is: project.yml, Config/, MediCabinet*/, Packages/,
  CHANGELOG.md                 #   .swiftlint.yml, .swiftlint-baseline.json; iOS changelog
android/
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradlew  gradle/
  gradle/libs.versions.toml
  config/detekt.yml  config/detekt-baseline.xml (app, core)
  core/                        # :core — pure Kotlin/JVM (+ testFixtures)
  app/                         # :app — Compose app
  CHANGELOG.md
scripts/
  local-ci.sh                  # shared checks + ios-local-ci + android-local-ci
  ios-local-ci.sh  ios-lint.sh  ios-check-strings.{sh,py}  ios-coverage-gate.{sh,py}
  android-local-ci.sh  android-check-architecture.py  android-check-strings.py
  android-coverage-gate.py  android-measure-launch.sh
  check-version.sh <ios|android>   check-fixtures.py   check-string-parity.py
  tests/                       # fixtures proving each rule / gate fails (existing + Android)
docs/  specs/  reports/
```

- Files are moved with `git mv` so history follows. Script names gain an `ios-`/`android-`
  prefix like Riding (`scripts/ios-lint.sh`); each iOS script `cd`s into `ios/`.
- `CHANGELOG.md` moves to `ios/CHANGELOG.md`; `android/CHANGELOG.md` is new.
  `check-version.sh ios` compares `ios/project.yml` `MARKETING_VERSION` with
  `ios/CHANGELOG.md`; `check-version.sh android` compares `versionName` in
  `android/app/build.gradle.kts` with `android/CHANGELOG.md`.
- `.gitignore`: iOS paths prefixed with `ios/`; Android adds `android/.gradle/`, `build/`,
  `android/local.properties`, `*.jks`, `*.keystore`.
- `CLAUDE.md`: new branch table (`ios/…`, `android/…`, `chore/…`, plain `feature/…` for both
  platforms), per-product version/tag/release steps (iOS: tag `ios/vX.Y.Z`; Android: tag
  `android/vX.Y.Z` + GitHub release "MediCabinet Android X.Y.Z" with the debug APK), product
  decision "Platforms: iOS and Android, native; behaviour tied by shared fixtures", updated
  structure, Common Task Map and commands. The "Test Frameworks" and "Performance" sections
  get an Android column.
- Tags: `ios/v0.1.0` is created on the commit `v0.1.0` points to (post-merge step).

## 3. Shared fixtures (S)

Every fixture file has the shape:

```json
{ "formatVersion": 1, "description": "…", "cases": [ … ] }
```

**`fixtures/expiry-dates.json`** — each case:

```json
{ "name": "leap February", "year": 2028, "month": 2, "day": null,
  "expect": { "lastValidDay": 29, "sortKey": "2028-02-29", "monthPrecision": true } }
{ "name": "April 31", "year": 2027, "month": 4, "day": 31,
  "expect": { "error": "invalidDay" } }
```

Seeded with every case of the current Swift `ExpiryDateTests` (month lengths, leap years,
2000, year bounds, invalid month/day/year, `DD=00` as `day: null`).

**`fixtures/schema-v1.json`** — tables → columns (name, declared type, not-null, default),
index names → (table, columns, unique, partial `WHERE` text), foreign keys. Each platform's
migration test introspects its freshly migrated database (`PRAGMA table_info`,
`index_list`, `index_info`, `foreign_key_list`, `sqlite_master.sql` for partial indexes) and
compares with this file — so the two schemas cannot drift.

Loading:

- **Swift:** `SharedFixtures` in `MediCabinetCoreTestSupport` resolves the repo root from
  `#filePath` and decodes the JSON (usable from core and app tests).
  `ExpiryDateFixtureTests` runs one parameterized test per case.
- **Kotlin:** `:core` test task sets the system property `medicabinet.fixtures` to
  `<repo>/fixtures`; `SharedFixtures` (in `:core` test fixtures) reads it with
  `kotlinx.serialization` (JSON). `ExpiryDateFixtureTest` uses a JUnit 5 `@TestFactory`, one
  dynamic test per case.
- `scripts/check-fixtures.py` validates every fixture (valid JSON, `formatVersion`, unique case
  names) in the shared step of `local-ci.sh`.

Cross-platform names (S6): expiry errors `invalidYear|invalidMonth|invalidDay`;
`RemovalReason` storage values `usedUp|thrownAway`; `ExpiryStatus`
`expired|expiringSoon|ok|unknown`; `CategoryID.uncategorized = "uncategorized"`.

## 4. Android project (A)

| Setting | Value |
|---------|-------|
| Gradle / AGP / Kotlin | Same versions as Riding's catalog at implementation time (AGP 9.3.1, Kotlin 2.2.21), pinned in `libs.versions.toml` |
| JVM | Gradle runs on the installed JDK 21 (no JDK 17 needed); bytecode and `sourceCompatibility` 17 (as Riding) |
| `applicationId` / namespace | `com.medicabinet.app` |
| SDK | `minSdk 26`, `compileSdk 36`, `targetSdk 36` (AGP installs Platform 36 on first build; licences already accepted) |
| Version | `versionName "0.1.0"`, `versionCode 1` |
| Modules | `:core` (`kotlin("jvm")` + `java-test-fixtures`), `:app` (`com.android.application`, Compose) |
| Dependencies (app) | Compose BOM + Material 3, `activity-compose`, `lifecycle-viewmodel-compose`, `navigation-compose`, `androidx.sqlite:sqlite-framework`, `kotlinx-coroutines-android` |
| Dependencies (core) | `kotlinx-coroutines-core` only; tests: JUnit 5, `kotlin-test`, `kotlinx-serialization-json` (test fixtures) |
| Signing | Debug keystore from the SDK; no release config committed (A3) |

### 4.1 Packages in `:app`

```
com.medicabinet.app
  MediCabinetApplication        # manual DI root (like RidingApp): builds Bootstrap
  MainActivity                  # setContent { MediCabinetTheme { RootScreen(...) } }
  app/        AppEnvironment, BootstrapViewModel, RootScreen, BootstrapErrorScreen, AppInfo, AppLog
  features/   MainScaffold (NavigationBar), placeholders/, settings/ (SettingsScreen, AboutScreen, AboutViewModel)
  persistence/  Migrations (v1 = iOS v1 SQL), CabinetDatabase (open helper), Rows, StoredDate,
              SqlLocationRepository, SqlBoxRepository, BackupService, Bootstrap
  ui/theme/   MediCabinetTheme (Material 3, dynamic colour off, light + dark)
```

### 4.2 App shell (A4–A8)

- `RootScreen` collects `BootstrapViewModel.state: StateFlow<BootstrapUiState>`
  (`Loading | Ready(AppEnvironment) | Failed(BootstrapFailure)`) and shows a progress
  indicator, `MainScaffold`, or `BootstrapErrorScreen` (title, message, **Retry**).
- `MainScaffold`: Material 3 `NavigationBar` with four `NavigationBarItem`s (icons from
  Material icons extended: `QrCodeScanner`, `MedicalServices`, `EventBusy`, `Settings`; R8 strips unused icons in release), Compose
  Navigation routes `scan`, `cabinet`, `expiring`, `settings`, `settings/about`.
- Placeholders: icon + title + message, same texts as iOS.
- About: name, `versionName (versionCode)` from an injected `AppInfo` (backed by
  `PackageManager`), disclaimer.
- Accessibility: all sizes in `sp`/`dp`, `contentDescription` / semantics on every icon-only
  element, 48 dp targets (Material 3 defaults), tested at font scale 2.0 and dark theme.

## 5. Kotlin core (K)

Package `com.medicabinet.core`:

```kotlin
class ExpiryDate private constructor(val year: Int, val month: Int, val day: Int?) : Comparable<ExpiryDate> {
    val isMonthPrecision: Boolean
    val lastValidDay: Int
    val sortKey: String                       // "YYYY-MM-DD", identical to iOS
    companion object {
        val SUPPORTED_YEARS = 2000..2099
        fun of(year: Int, month: Int, day: Int?): ExpiryDate   // throws InvalidExpiryDateException
    }
    // equals / hashCode / toString on (year, month, day)
}
sealed interface ExpiryDateError { val kind: String       // "invalidYear" | "invalidMonth" | "invalidDay"
    data class InvalidYear(val year: Int); data class InvalidMonth(val month: Int)
    data class InvalidDay(val day: Int, val month: Int, val year: Int) }
class InvalidExpiryDateException(val error: ExpiryDateError) : IllegalArgumentException()

fun interface Clock { fun now(): Instant }    // java.time (API 26+)
object SystemClock : Clock; class FixedClock(…) : Clock; class MutableClock (test fixtures)

@JvmInline value class CategoryID(val rawValue: String) { companion object { val UNCATEGORIZED } }
data class ProductRef(val gtin: String?, val name: String)
enum class ExpiryStatus(val rawValue: String) { EXPIRED("expired"), EXPIRING_SOON("expiringSoon"), OK("ok"), UNKNOWN("unknown") }
enum class RemovalReason(val rawValue: String) { USED_UP("usedUp"), THROWN_AWAY("thrownAway") }
data class Location(val id: UUID, val name: String, val isDefault: Boolean, val sortOrder: Int, val createdAt: Instant, val updatedAt: Instant)
data class Box(… same fields as iOS …, val removal: Removal?) { data class Removal(val reason: RemovalReason, val date: Instant); val isInCabinet }

interface LocationRepository { suspend fun all(); suspend fun defaultLocation(); suspend fun insert(location); suspend fun ensureDefault(name) }
interface BoxRepository { suspend fun activeBoxes(); suspend fun box(id); suspend fun insert(box); suspend fun update(box); suspend fun markRemoved(id, reason, at); suspend fun undoRemoval(id) }
enum class RepositoryError { NOT_FOUND, UNKNOWN_LOCATION, DUPLICATE, DUPLICATE_DEFAULT }
class RepositoryException(val error: RepositoryError) : Exception()
```

Same semantics as iOS §3.2 (ordering, `updatedAt` stamped from the injected clock, `update`
keeps `createdAt` and `removal`). `java.time.Instant` is truncated to milliseconds on write
(SQLite storage precision, as GRDB does on iOS).

**Test fixtures** (`core/src/testFixtures`): `MutableClock`, `InMemoryCabinetStore`,
`RepositoryContract` (the same 13 checks as the Swift `RepositoryContract`, same names),
`SharedFixtures`. Consumed by `:app` tests with `testImplementation(testFixtures(project(":core")))`.

## 6. Persistence (P)

- **Location:** `context.getDatabasePath("cabinet.db")` (internal `databases/`), backups in
  `filesDir/backups/` — internal storage only (P8). Internal storage is encrypted at rest by
  the device: file-based encryption (credential-encrypted storage, readable after the first
  unlock — the equivalent of iOS `completeUntilFirstUserAuthentication`) is required on
  devices launching with Android 10+; older devices use file-based or full-disk encryption.
- **Schema v1:** `Migrations.v1` holds the iOS v1 `CREATE TABLE` / `CREATE INDEX` statements
  verbatim (`DATETIME` columns, CHECKs, partial unique index, `ON DELETE RESTRICT`).
  `CabinetDatabase` wraps a `SupportSQLiteOpenHelper` whose callback, on every connection, turns
  foreign keys on and enables WAL (`onConfigure`); creates the schema by running every migration
  (`onCreate`); and runs the pending ones (`onUpgrade`). The platform open helper runs
  `onCreate`/`onUpgrade` in one transaction and sets `user_version` at the end, so a failing
  migration leaves the database unchanged. The open helper never drops data (no destructive path
  exists in the code; `onDowngrade` throws). Future migrations append to `Migrations.all`; a test
  proves that upgrading step by step from every earlier version yields the same schema as a fresh
  install.
- **Repositories:** `SqlLocationRepository`, `SqlBoxRepository` implement the core ports on
  `Dispatchers.IO`, check existence inside one transaction (same checks as GRDB), map rows
  to core types and throw `PersistenceException.CorruptRow` on unreadable rows (never repaired).
- **Pending-migration detection (before opening):** if `cabinet.db` exists, open it briefly
  with the framework `SQLiteDatabase` (read-write, no version change), read
  `PRAGMA user_version`, run `PRAGMA wal_checkpoint(TRUNCATE)`, close. `0 < user_version <
  Migrations.all.size` → migrations pending.
- **BackupService:** with the database closed and checkpointed, copy `cabinet.db` (single
  self-contained file after the checkpoint) to
  `backups/pre-migration-<UTC yyyyMMdd-HHmmss>-v<user_version>.db`; unique names on
  collision; prune to the newest 5 by file name; any failure → `BackupException`. (SQLite's
  `VACUUM INTO` would avoid the checkpoint but needs SQLite 3.27, absent on Android 8–10.)
- **Bootstrap** (`suspend fun run(): BootstrapState` on `Dispatchers.IO`): detect → back up
  (failure → `Failed(BACKUP)`, no migration) → open `CabinetDatabase`, which creates or migrates
  inside the open helper's transaction (failure → close it, delete
  `cabinet.db`, `-wal`, `-shm`, copy the backup back → `Failed(MIGRATION)`) →
  `ensureDefault(localized name)` → prune → `Ready(AppEnvironment)`. Any other I/O failure →
  `Failed(STORAGE)`. Never deletes the database except to replace it with its backup.
  **Corruption safety (found in T17):** the platform's `SupportSQLiteOpenHelper.Callback.onCorruption`
  and the default `DatabaseErrorHandler` *delete* a database they consider corrupt. `CabinetDatabase`
  overrides `onCorruption` to throw, and `BackupService` opens the existing file with a no-op error
  handler, so an unreadable file is reported (`STORAGE`) and left untouched; a mutation check proves the
  regression test fails if the file is ever deleted. iOS has the same guarantee (GRDB never deletes),
  now tested there too.
- **Auto Backup (I3):** `allowBackup="true"`, `fullBackupContent="@xml/backup_rules"`
  (API ≤ 30) and `dataExtractionRules="@xml/data_extraction_rules"` (API 31+) include the
  `database` domain and `file` domain `backups/` for cloud backup and device transfer.

## 7. Gates (G)

`scripts/android-local-ci.sh` (from `android/`, `set -euo pipefail`, named `▶`/`✗` steps
as on iOS):

1. Prerequisites: `java` 17+ and an Android SDK (`ANDROID_HOME`, else `local.properties`,
   else `~/Library/Android/sdk`) → install hints otherwise.
2. `./gradlew :app:assembleDebug`
3. `./gradlew detekt ktlintCheck` (baselines committed, new findings only)
4. `python3 -I scripts/android-check-architecture.py` (+ its fixture test)
5. `./gradlew :core:test :app:testDebugUnitTest` (JUnit 5 in core; JUnit 4 + Robolectric +
   Compose UI tests in app)
6. `./gradlew jacocoReport` → `scripts/android-coverage-gate.py` (≥ 80% lines for `:core`
   main sources and for `:app`, excluding Compose previews and `R`/`BuildConfig`)
7. `python3 -I scripts/android-check-strings.py` (+ fixture test)
8. `bash scripts/check-version.sh android`
9. `./gradlew :app:compileDebugAndroidTestKotlin` (instrumented tests compile; running them
    needs an emulator and is optional, as in Riding)

**Architecture rules** (`android-check-architecture.py`, regex over `.kt` files + manifest,
same approach and limits as the iOS SwiftLint custom rules, fixtures under
`scripts/tests/android-architecture/`):

| Rule | Fails on |
|------|----------|
| `core_forbidden_imports` | `import android.` or `androidx.` in `:core` |
| `core_no_wall_clock` | `Instant.now(`, `System.currentTimeMillis(`, `LocalDate.now(`, `Clock.system` in `:core` main, except `SystemClock.kt` |
| `sql_confined_to_persistence` | `androidx.sqlite` / `android.database` imports outside `persistence/` |
| `no_network` | `java.net.URL`, `HttpURLConnection`, `okhttp3`, `retrofit2`, `ktor` imports; `android.permission.INTERNET` in any manifest |
| `log_through_applog` | `android.util.Log` outside `AppLog.kt` |

**String check** (`android-check-strings.py`): `values/strings.xml` (fr) and
`values-en/strings.xml` have identical key sets, no empty values, identical placeholder
signatures. **Parity check** (`check-string-parity.py`, shared step): for every Android key
whose dotted form exists in the iOS catalog (`tab_scan` ↔ `tab.scan`), the fr and en values
are equal after placeholder normalization (`%1$s` ↔ `%1$@`) — so both apps say the same
thing.

`scripts/local-ci.sh`: shared step (`check-fixtures.py`, `check-string-parity.py`, script
fixture tests) → `ios-local-ci.sh` → `android-local-ci.sh`. Each product gate also runs alone.

## 8. Privacy and logging (I2)

- Manifest declares **no permission at all** in 0.1.0 (`INTERNET` absent → no network
  possible, checked by the architecture rule and a Robolectric test reading the merged
  manifest).
- `AppLog` is the only logger: `AppLog.bootstrapFailed(kind, error)` logs the failure kind
  and `error::class.simpleName` only — never `error.message`, which could carry data
  (Android has no private redaction like `os.Logger`).
- No analytics / crash / ads dependency (dependency list reviewed in the PR).

## 9. Localization (I1, I4)

- `res/values/strings.xml` = French (fallback), `res/values-en/strings.xml` = English;
  `res/xml/locales_config.xml` (fr, en) + `android:localeConfig` so Android 13+ offers the
  per-app language setting. Keys mirror iOS with underscores (`bootstrap_error_backup_title`).
- `versionName`/`versionCode` in About use `%1$s (%2$s)` → parity-checked with iOS.

## 10. Sequence — key scenarios

Same four scenarios as iOS design §7 (first launch, upgrade with pending migration, backup
cannot be written, migration fails), with the Android detection step (user_version +
checkpoint) before the driver opens.

## 11. Performance

| Target | How |
|--------|-----|
| Cold start < 1 s | `scripts/android-measure-launch.sh`: `adb shell am start -W` × 5 on the emulator, average recorded in `reports/android-foundation/README.md` (measured, not gated) |
| No I/O on main | Repositories and Bootstrap on `Dispatchers.IO`; a test asserts the thread |
| Indexed queries | Same indexes as iOS (via `schema-v1.json`); `EXPLAIN QUERY PLAN` test on active boxes and GTIN lookup |

## 12. Test strategy

| Requirement | Test | Kind |
|-------------|------|------|
| M1–M3 | `local-ci.sh` green after the move; iOS coverage ≥ the 0.1.0 numbers | CI |
| M4–M6 | Review checklist (CLAUDE.md, branch table) | review |
| M5 | `check-version.sh ios` / `android` + broken fixtures | script |
| S1–S5 | `check-fixtures.py`; `ExpiryDateFixtureTests` (Swift) and `ExpiryDateFixtureTest` (Kotlin) over the same file; a deliberately wrong case in `scripts/tests/fixtures/` proves both suites fail | unit + script |
| S6 | Kotlin test asserts raw values/kinds equal the strings in `expiry-dates.json` and the iOS enum raw values listed in `fixtures/README.md` | unit |
| A1–A3 | `assembleDebug` from clean; no signing config in Gradle files (review) | CI |
| A4, A5 | Robolectric Compose test: 4 navigation items, each placeholder title shown | UI (JVM) |
| A6 | `AboutViewModelTest` (fake `AppInfo`, fr/en), Compose test of About | unit + UI |
| A7 | Compose test at `fontScale = 2f`, dark theme: items still displayed and clickable; semantics labels present | UI |
| A8 | `BootstrapViewModelTest` (Turbine) + `BootstrapErrorScreen` Compose test per failure kind, Retry calls run again | unit + UI |
| K1–K6 | `:core` JUnit 5 tests mirroring the Swift suites; `RepositoryContract` against `InMemoryCabinetStore` | unit |
| P1 | `SchemaTest` vs `fixtures/schema-v1.json`; constraint tests mirroring iOS `MigrationTests` | integration (Robolectric) |
| P2 | `MigrationsTest`: step-by-step upgrade from every version equals a fresh install; `onDowngrade` throws; no destructive path in code | integration |
| P3–P5, P9 | `BackupServiceTest`, `BootstrapTest` (first launch, upgrade with a test schema v2, backup failure, migration failure restores, first-launch failure then Retry) | integration |
| P6 | Thread assertion test; architecture rule | unit + script |
| P7, P10 | `RepositoryContract` against `SqlLocationRepository` / `SqlBoxRepository` | integration |
| P8 | Test that database and backups paths are under `filesDir`/`databases` | unit |
| G1–G5 | Each script run against broken fixtures under `scripts/tests/` | script |
| I1 | `android-check-strings.py` + `check-string-parity.py` with fixtures | script |
| I2 | Merged-manifest test (no permissions); architecture rule; `AppLog` test | unit |
| I3 | Test reading `data_extraction_rules.xml` / `backup_rules.xml`: database and `backups/` included | unit |
| I5 | `check-version.sh android` | script |

## 13. i18n impact

Android strings: every iOS 0.1.0 key, renamed with underscores, same fr/en values. No new
iOS strings.

## 14. Risks

- **Hand-written SQL** fails at run time, not compile time: every statement is exercised by the
  repository contract, schema and storage tests; a new query without a test is a review blocker.
- **Auto Backup and WAL:** the backup agent copies files while the app is stopped; a
  checkpoint on app stop reduces the window. Restore tested manually once with
  `adb shell bmgr`.
- **Robolectric Compose tests** cover UI on the JVM; real-device behaviour (TalkBack, font
  scale) still needs one manual pass before release.
- The restructure touches every iOS path: one dedicated commit, iOS gate run before and after.
