# Foundation — Tasks

Status: **Draft — awaiting approval of `requirements.md` and `design.md` first**
Epic: #1 · Branch: `feature/1-foundation`

One task = one focused commit (code + tests + this file checked off). Order matters: each
task unblocks the next. Child issue in brackets; requirement IDs refer to
[`requirements.md`](requirements.md).

## Project and core (#2, #3)

- [ ] **T1 — Core package skeleton** [#3] (R3.1)
  `Packages/MediCabinetCore` with `Package.swift` (tools 6.0, iOS 17 / macOS 14), empty
  library, one smoke test. *Test:* `swift test` passes.
- [ ] **T2 — `ExpiryDate` and `ExpiryDateError`** [#3] (R3.4, R3.5)
  *Tests first:* `ExpiryDateTests` — day vs month precision, leap years, invalid month/day/year,
  last valid day of every month.
- [ ] **T3 — `Clock`, `SystemClock`, `FixedClock`** [#3] (R3.7)
  *Tests:* `FixedClock` returns the fixed date; advancing works.
- [ ] **T4 — Domain model: `Location`, `CategoryID`, `ProductRef`, `Box`, `RemovalReason`,
  `ExpiryStatus`** [#3] (R3.2, R3.3, R3.6)
  *Tests:* `BoxTests`, `LocationTests` — identity, nil expiry, removal value semantics,
  `CategoryID.uncategorized`.
- [ ] **T5 — Repository ports** [#3] (R4.5)
  `LocationRepository`, `BoxRepository` protocols + an in-memory fake in the core test target
  used to test the port contract. *Tests:* contract tests runnable against any implementation.
- [ ] **T6 — XcodeGen project and signing config** [#2] (R1.1–R1.4)
  `project.yml`, `Config/Signing.xcconfig`, `Signing.local.xcconfig.sample`, app/unit/UI
  targets, GRDB pinned, core linked. Minimal `MediCabinetApp` showing a placeholder.
  *Test:* `xcodegen generate && xcodebuild build` on simulator; one unit + one UI smoke test.

## Persistence (#4)

- [ ] **T7 — `AppDatabase` and migration `v1`** [#4] (R4.1, R4.2, R4.4)
  Pool creation, directory + file protection, `v1` schema and indexes (design §4).
  *Tests first:* `MigrationTests` (tables/indexes/constraints), `AppDatabaseTests` (file
  protection), `EXPLAIN QUERY PLAN` index-use test.
- [ ] **T8 — GRDB repositories** [#4] (R4.5–R4.7, R6.1, R6.2)
  Records + `GRDBLocationRepository`, `GRDBBoxRepository` incl. `ensureDefault`,
  `markRemoved`, `undoRemoval`. *Tests:* core contract tests (T5) run against GRDB on a temp
  DB; second-default rejected; ensureDefault idempotent.
- [ ] **T9 — `BackupService`** [#4] (R5.1, R5.3, R5.4)
  Online backup, file naming, prune to 5. *Tests first:* backup on pending migration, none on
  fresh DB, pruning keeps newest 5, backup file opens and contains rows.
- [ ] **T10 — `Bootstrap` state machine** [#4] (R4.1, R4.3, R4.6, R5.2, R6.1)
  Steps of design §3.4 with injectable migrator/backup for failure tests.
  *Tests first:* first launch, upgrade, backup failure (no migration), migration failure
  (backup restored, checksum equal), off-main-actor assertion.

## App shell (#2)

- [ ] **T11 — Root view, blocking error screen, tab shell and placeholders** [#2]
  (R2.1, R2.2, R2.4, R4.3, R5.2)
  `RootView`, `MainTabView`, placeholders with `ContentUnavailableView`, Retry.
  *Tests:* `MainTabUITests` (4 tabs, placeholders), accessibility-size + dark-mode UI test,
  unit test for the error-state view model.
- [ ] **T12 — Settings and About** [#2] (R2.3, R8.3)
  `AppInfo` abstraction, `AboutView` + view model, disclaimer.
  *Tests:* `AboutViewModelTests` (fake `AppInfo`, fr/en formatting), `AboutUITests`.

## Localization, privacy, tooling, docs (#5, #6)

- [ ] **T13 — String Catalogs and `check-strings.sh`** [#6] (R8.1, R8.2)
  All keys of design §10 in fr + en, `InfoPlist.xcstrings`; check script + broken fixture
  test under `scripts/tests/`.
- [ ] **T14 — Privacy manifest and logging** [#6] (R9.1–R9.3)
  `PrivacyInfo.xcprivacy`, `Logger` helper with `.private` usage. *Test:* manifest present in
  bundle.
- [ ] **T15 — SwiftLint config, custom rules and `lint.sh`** [#5] (R3.1, R3.7, R4.5)
  `.swiftlint.yml` (incl. forbidden imports in core, `Date()` in core, `import GRDB` outside
  `Persistence/`), empty baseline. *Test:* fixture files trigger each custom rule.
- [ ] **T16 — Coverage gate and version check scripts** [#5] (R7.3, R7.4)
  `coverage-gate.sh`, `check-version.sh` + broken fixtures under `scripts/tests/`.
- [ ] **T17 — `local-ci.sh`** [#5] (R7.1, R7.2, R7.5)
  Orchestrates all steps with named failures and missing-tool messages. *Test:* full run
  green on the branch; missing-tool path checked with a stubbed `PATH`.
- [ ] **T18 — Docs, README and CHANGELOG 0.1.0** [#6] (R10.1–R10.3)
  `docs/README.md`, `docs/architecture.md`, `docs/development.md`, README build/install
  section, `CHANGELOG.md` `## 0.1.0 — <date>`; record launch-metric result in
  `reports/foundation/README.md`. *Test:* `check-version.sh` green.

## Wrap-up

- [ ] **T19 — Full suite and coverage** — run `bash scripts/local-ci.sh`; state core and app
  coverage % in the PR body; update the Common Task Map and Build & Test Commands in
  `CLAUDE.md`; remove "to be created" notes that are now true.
- [ ] **T20 — Version bump commit** — confirm `0.1.0` / build `1` in `project.yml` and
  `CHANGELOG.md` (last commit on the branch). PR to `develop` only when the user asks.
