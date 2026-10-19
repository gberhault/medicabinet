# Android foundation — Tasks

Status: **Approved 2026-10-19**
Epic: #31 · Branch: `android/feature/31-android-foundation`

One task = one focused commit (code + tests + this file checked off). Child issue in
brackets; requirement IDs refer to [`requirements.md`](requirements.md), sections to
[`design.md`](design.md).

Order: restructure and shared fixtures first, so iOS is never broken and both cores are tested
against the same files from the start. The Android scaffold (T6) proves SQLDelight builds with
AGP 9 **before** anything depends on it — if it does not, stop and revisit the design (§14).

## 1. Monorepo and shared fixtures (#32, #33)

- [x] **T1 — Move iOS into `ios/`** [#32] (M1, M2)
  `git mv` project.yml, Config, MediCabinet*, Packages, SwiftLint config/baseline into `ios/`;
  rename scripts with the `ios-` prefix (`ios-local-ci.sh`, `ios-lint.sh`,
  `ios-check-strings.*`, `ios-coverage-gate.*`), each working from `ios/`; fix `.gitignore`
  and script-test paths. *Test:* `bash scripts/ios-local-ci.sh` green with the same test count
  and coverage ≥ 100% core / 92.4% app.
- [x] **T2 — Per-product versions and changelogs** [#32] (M5)
  `CHANGELOG.md` → `ios/CHANGELOG.md`; `check-version.sh <ios|android>`; fixtures for both
  products. *Test:* gate fixtures (match, mismatch, missing entry, unknown product).
- [x] **T3 — Shared expiry fixture and Swift loader** [#33] (S1–S5)
  `fixtures/README.md`, `fixtures/expiry-dates.json` (all current Swift cases),
  `scripts/check-fixtures.py` + fixture test; `SharedFixtures` in
  `MediCabinetCoreTestSupport`; `ExpiryDateFixtureTests` (one case per fixture entry).
  *Test:* a broken fixture under `scripts/tests/fixtures/` makes the Swift suite fail.
- [ ] **T4 — Shared schema fixture, checked on iOS** [#33] (S1, S3, P1)
  `fixtures/schema-v1.json` (tables, columns, indexes incl. partial, foreign keys);
  iOS `SchemaFixtureTests` introspects a migrated DB and compares. *Test:* passes on iOS; a
  modified copy of the fixture fails.
- [ ] **T5 — Root `local-ci.sh` and CLAUDE.md monorepo rules** [#32] (M3, M4, M6)
  `scripts/local-ci.sh` = shared step (check-fixtures, script fixture tests) → iOS gate
  (Android gate added in T22). CLAUDE.md: platforms decision, branch table, per-product
  version/tag/release steps, structure and commands. *Test:* `local-ci.sh` green.

## 2. Android scaffold and gates (#34, #37)

- [ ] **T6 — Gradle project with `:core` and `:app`** [#34] (A1–A3)
  Wrapper, `libs.versions.toml` (Riding's AGP/Kotlin, SQLDelight 2.4.1, Compose BOM),
  `:core` (JVM + test fixtures), `:app` (namespace/applicationId `com.medicabinet.app`,
  minSdk 26, target/compile 36, 0.1.0 / 1), SQLDelight plugin applied with an empty
  database, minimal `MainActivity`. *Test:* `./gradlew :app:assembleDebug :core:test` from a
  clean checkout; one JVM smoke test per module.
- [ ] **T7 — Android architecture checker** [#37] (G4, G5, K1, P6)
  `scripts/android-check-architecture.py` with the five rules of design §7; fixtures under
  `scripts/tests/android-architecture/` (one failing file per rule + a clean one).
- [ ] **T8 — detekt and ktlint** [#37] (G1)
  `config/detekt.yml`, empty baselines, ktlint Gradle plugin. *Test:* both green; a
  formatting fixture fails ktlint.
- [ ] **T9 — `android-local-ci.sh` (first version)** [#37] (G1 partial, G2)
  Prerequisites (JDK 17+, SDK lookup) with install hints → assemble → detekt/ktlint →
  architecture → unit tests → androidTest compile; named steps. *Test:* green; stubbed `PATH`
  without `java` fails with the hint.

## 3. Kotlin core (#35)

- [ ] **T10 — `ExpiryDate` and its errors** [#35] (K2, K3, S3, S6)
  *Tests first:* `ExpiryDateFixtureTest` (dynamic test per case of the shared fixture) +
  Kotlin-specific tests (equality, ordering, kinds).
- [ ] **T11 — `Clock`, `SystemClock`, `FixedClock`, `MutableClock`** [#35] (K5)
  `SystemClock` is the only allowed wall-clock read. *Tests:* fixed / advancing /
  non-decreasing.
- [ ] **T12 — Domain model v1** [#35] (K2, K4, S6)
  `Location`, `CategoryID`, `ProductRef`, `Box` (+ `Removal`), `RemovalReason`,
  `ExpiryStatus` with the shared raw values. *Tests:* mirror Swift `ModelTests`; raw values
  equal the strings listed in `fixtures/README.md`.
- [ ] **T13 — Repository ports, in-memory fake, contract suite** [#35] (K6)
  Ports, `RepositoryError`/`RepositoryException`, `InMemoryCabinetStore`,
  `RepositoryContract` (same 13 checks, same names as Swift) in test fixtures. *Test:*
  contract passes against the fake.

## 4. Persistence (#36)

- [ ] **T14 — SQLDelight schema v1 and driver** [#36] (P1, P2, P8)
  `Location.sq` / `Box.sq` = iOS v1 statements + named queries; driver with foreign keys and
  WAL; database in internal storage. *Tests first (Robolectric):* `SchemaTest` vs
  `fixtures/schema-v1.json`; constraint tests mirroring iOS `MigrationTests`; `EXPLAIN QUERY
  PLAN` index use; paths under internal storage; `verifySqlDelightMigration` green.
- [ ] **T15 — SQL repositories** [#36] (P6, P7, P10)
  `SqlLocationRepository`, `SqlBoxRepository` on `Dispatchers.IO`, transactional checks,
  `CorruptRow` on unreadable rows. *Tests:* `RepositoryContract` against them; stored
  representation (text UUIDs, expiry parts + sort key, removal kept); corrupt row reported.
- [ ] **T16 — `BackupService`** [#36] (P3)
  Detection (user_version + checkpoint), copy, naming, prune to 5. *Tests first:* new DB → no
  copy; up to date → no copy; pending → one self-contained copy that opens and holds the rows;
  same-second collision; unwritable folder → `BackupException`; pruning.
- [ ] **T17 — `Bootstrap` with restore** [#36] (P3–P6, P9)
  Design §6 sequence with an injectable schema for tests. *Tests first:* first launch;
  upgrade (test schema v2); backup failure → no migration; migration failure → copy restored,
  rows identical; first-launch failure then Retry; work off the main thread; default location
  created once.

## 5. App shell (#34)

- [ ] **T18 — Theme, root, error screen, navigation and placeholders** [#34]
  (A4, A5, A7, A8)
  `MediCabinetTheme`, `BootstrapViewModel` (StateFlow), `RootScreen`, `BootstrapErrorScreen`,
  `MainScaffold` with four destinations, placeholders; fr/en strings for these screens.
  *Tests:* `BootstrapViewModelTest` (Turbine: loading → ready / failed, Retry); Robolectric
  Compose tests (4 items, each placeholder, error screen per kind, font scale 2.0 + dark).
- [ ] **T19 — Settings and About** [#34] (A6, I4)
  `AppInfo` (PackageManager-backed), `AboutViewModel`, `SettingsScreen`, `AboutScreen`.
  *Tests:* view model with fake `AppInfo` in fr and en; Compose test of About.

## 6. Localization, privacy, remaining gates (#37, #38)

- [ ] **T20 — String checks and per-app language** [#38] (I1)
  `android-check-strings.py` (key parity, empty values, placeholders) and
  `check-string-parity.py` (Android ↔ iOS wording), fixtures for both;
  `locales_config.xml` + `android:localeConfig`.
- [ ] **T21 — Privacy: no permissions, AppLog, Auto Backup rules** [#38] (I2, I3)
  Manifest with no permissions, `AppLog` (failure kind + exception class only),
  `backup_rules.xml` / `data_extraction_rules.xml`. *Tests:* merged-manifest test, `AppLog`
  test, backup-rules test (database + `backups/` included).
- [ ] **T22 — Coverage gate, Android version check, full gate wiring** [#37] (G1, G3, I5)
  Jacoco report for `:core` and `:app` (generated code excluded), `android-coverage-gate.py`
  (≥ 80% lines each) + fixtures; `check-version.sh android` and `verifySqlDelightMigration` in
  `android-local-ci.sh`; Android gate and string parity added to `local-ci.sh`.

## 7. Docs and wrap-up (#38)

- [ ] **T23 — Docs, README, Android CHANGELOG, launch measurement** [#38] (I5, I6)
  `docs/architecture.md` and `docs/development.md` for both platforms, README Android build
  and install, `android/CHANGELOG.md` `## 0.1.0`, `scripts/android-measure-launch.sh` and its
  result in `reports/android-foundation/README.md`.
- [ ] **T24 — Full root gate and CLAUDE.md refresh** — `bash scripts/local-ci.sh` green
  (shared + iOS + Android); coverage % for the PR body; Common Task Map and Build & Test
  Commands cover Android.
- [ ] **T25 — Version confirmation commit** — Android 0.1.0 / 1 and its CHANGELOG match; iOS
  unchanged at 0.1.0. PR to `develop` only when asked. **After merge:** tag `ios/v0.1.0` (on
  the `v0.1.0` commit) and `android/v0.1.0`, GitHub release "MediCabinet Android 0.1.0" with
  the debug APK (I7).
