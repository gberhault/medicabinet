# Foundation — Tasks

Status: **Draft — awaiting approval** (requirements and design approved 2026-10-19)
Epic: #1 · Branch: `feature/1-foundation`

One task = one focused commit (code + tests + this file checked off). Order matters: each
task unblocks the next. Child issue in brackets; requirement IDs refer to
[`requirements.md`](requirements.md), sections to [`design.md`](design.md).

Quality gates come first (T3–T4) so every later commit is built, linted and tested by
`scripts/local-ci.sh`; the coverage, strings and version checks are added once there is code
for them to measure (T15–T16).

## 1. Skeleton and gates (#2, #3, #5)

- [ ] **T1 — Core package skeleton** [#3] (R3.1)
  `Packages/MediCabinetCore` with `Package.swift` (tools 6.0, iOS 17 / macOS 14), empty
  library, one smoke test. *Test:* `swift test` passes.
- [ ] **T2 — XcodeGen project and signing config** [#2] (R1.1–R1.4)
  `project.yml` (design §2: `com.medicabinet.app`, display name MediCabinet, 0.1.0 / 1),
  `Config/Signing.xcconfig`, `Signing.local.xcconfig.sample`, app/unit/UI targets, GRDB pinned
  exactly, core linked. Minimal `MediCabinetApp` with a placeholder view.
  *Test:* `xcodegen generate && xcodebuild build` on a simulator **without**
  `Signing.local.xcconfig`; one unit + one UI smoke test.
- [ ] **T3 — SwiftLint config, custom rules and `lint.sh`** [#5] (R2.5, R3.1, R3.7, R4.5)
  `.swiftlint.yml` with custom rules: no UIKit/SwiftUI/VisionKit/GRDB import in core,
  no `Date()` in core `Sources/`, `import GRDB` only under `Persistence/`, no
  `URLSession` / `URLRequest` / `import Network` anywhere; empty baseline.
  *Test:* fixture files under `scripts/tests/lint/` each trigger their rule (non-zero exit).
- [ ] **T4 — `local-ci.sh` (first version)** [#5] (R7.1 partial, R7.2, R7.5)
  Steps: generate → lint → `swift test` → `xcodebuild test` on the first available iPhone
  simulator; named `▶`/`✗` steps; missing `xcodegen`/`swiftlint` → install hint.
  *Test:* green on the branch; stubbed `PATH` without `swiftlint` fails with the hint.

## 2. Core domain (#3)

- [ ] **T5 — `ExpiryDate` and `ExpiryDateError`** [#3] (R3.4, R3.5)
  *Tests first:* `ExpiryDateTests` — day vs month precision, last valid day of every month,
  Feb 28/29 (2028), invalid month 0/13, day 0/32, Apr 31, year outside 2000…2099.
- [ ] **T6 — `Clock`, `SystemClock`, `FixedClock`** [#3] (R3.7)
  *Tests:* `FixedClock` returns the fixed date; `SystemClock` is non-decreasing across two
  reads.
- [ ] **T7 — Domain model** [#3] (R3.2, R3.3, R3.6)
  `Location`, `CategoryID` (+ `.uncategorized`), `ProductRef`, `Box` (+ `Removal`),
  `RemovalReason`, `ExpiryStatus`. *Tests:* `BoxTests`, `LocationTests` — identity, nil
  expiry, removal semantics, category key round-trip.
- [ ] **T8 — Repository ports and contract tests** [#3] (R4.5)
  `LocationRepository`, `BoxRepository` (design §3.2) + in-memory fake; reusable contract test
  suite runnable against any implementation.

## 3. Persistence (#4)

- [ ] **T9 — `AppDatabase` and migration `v1`** [#4] (R4.1, R4.2, R4.4)
  Pool creation, directory + `completeUntilFirstUserAuthentication`, `v1` schema and indexes
  (design §4). *Tests first:* `MigrationTests` (tables, indexes, every CHECK and the
  single-default index reject bad rows), `AppDatabaseTests` (file protection attribute),
  `EXPLAIN QUERY PLAN` index-use test for active boxes and GTIN lookup.
- [ ] **T10 — GRDB repositories** [#4] (R4.5–R4.7, R6.1, R6.2)
  Records + `GRDBLocationRepository`, `GRDBBoxRepository` (`ensureDefault`, `markRemoved`,
  `undoRemoval`, `updatedAt` from the clock). *Tests:* T8 contract suite against GRDB on a
  temp DB; `ensureDefault` idempotent; second default rejected.
- [ ] **T11 — `BackupService`** [#4] (R5.1, R5.3, R5.4)
  Online backup, file naming, prune to 5. *Tests first:* backup on pending migration; none
  on a fresh DB; 7 backups → newest 5 kept; backup file opens and contains the rows.
- [ ] **T12 — `Bootstrap` state machine and restore** [#4] (R4.1, R4.3, R4.6, R5.2, R6.1)
  Design §3.4 incl. restore procedure (close pool, replace file, drop `-wal`/`-shm`), with
  injectable migrator/backup. *Tests first:* first launch; upgrade; backup failure → no
  migration; migration failure → backup restored, checksum equal; first-launch failure →
  Retry succeeds; work runs off the main actor.

## 4. App shell (#2)

- [ ] **T13 — Root view, blocking error screen, tabs and placeholders** [#2]
  (R2.1, R2.2, R2.4, R4.3, R5.2)
  `RootView`, `MainTabView` (Scan · Cabinet · Expiring · Settings), `ContentUnavailableView`
  placeholders, error screen with Retry. *Tests:* `MainTabUITests`; accessibility-size + dark
  mode UI test; error-state view-model unit test.
- [ ] **T14 — Settings and About** [#2] (R2.3, R8.3)
  `AppInfo` abstraction, `AboutView` + view model, disclaimer. *Tests:* `AboutViewModelTests`
  (fake `AppInfo`, fr/en formatting), `AboutUITests` (version and build match the bundle).

## 5. Localization, privacy, remaining gates (#5, #6)

- [ ] **T15 — String Catalogs and `check-strings.sh`** [#6] (R8.1, R8.2)
  All keys of design §10 in fr + en, `InfoPlist.xcstrings` (`CFBundleDisplayName`
  MediCabinet); script added to `local-ci.sh`. *Test:* fixture catalog with a missing `en`
  value fails.
- [ ] **T16 — Coverage gate and version check** [#5] (R7.1, R7.3, R7.4)
  `coverage-gate.sh` (core + app ≥ 80% lines), `check-version.sh`, both added to
  `local-ci.sh`. *Tests:* broken fixtures under `scripts/tests/` fail each script.
- [ ] **T17 — Privacy manifest and logging** [#6] (R9.1–R9.3)
  `PrivacyInfo.xcprivacy` (no tracking, no collected data, no required-reason API),
  `Logger` helper using `.private`. *Test:* manifest present in the app bundle.

## 6. Docs and wrap-up (#6)

- [ ] **T18 — Docs, README and CHANGELOG 0.1.0** [#6] (R10.1–R10.3)
  `docs/README.md`, `docs/architecture.md` (layers, DB location, migrations, backups, device
  backups), `docs/development.md` (setup, commands, lint-rule limits), README build/install,
  `CHANGELOG.md` `## 0.1.0 — <date>`; launch-metric result in `reports/foundation/README.md`.
- [ ] **T19 — Full suite and CLAUDE.md refresh** — `bash scripts/local-ci.sh` green; core and
  app coverage % noted for the PR body; fill the Common Task Map and Build & Test Commands in
  `CLAUDE.md`; drop its "to be created" wording.
- [ ] **T20 — Version confirmation commit** — `project.yml` 0.1.0 / build 1 and `CHANGELOG.md`
  match (last commit on the branch). Close child issues as each completes; PR to `develop`
  only when the user asks.
