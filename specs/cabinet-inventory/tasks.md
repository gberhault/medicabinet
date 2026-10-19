# Cabinet inventory, locations and stock movements — Tasks

Status: **Approved 2026-10-19**
Epic: #17 · Branch: `feature/17-cabinet-inventory`

One task = one focused commit (code + tests + this file checked off). Tags: `[shared]`,
`[ios]`, `[android]`. Child issue in brackets; requirement IDs refer to
[`requirements.md`](requirements.md), sections (§) to [`design.md`](design.md).

**Before T1:** epic #12 is merged into `develop` and this branch is rebased on it (decision 1).
Tasks that need #23 or #10 are marked **blocked** and wait for them; everything else can be
built first.

Order: shared fixtures → both cores → schema v3 and repository additions → observation →
locations UI → Cabinet list → box detail → remove mode → docs, measurements, version. The
iOS and Android UI blocks of each section are independent of each other.

## 0. Preparation

- [ ] **T0 — Rebase on `develop` with #12** `[shared]`
  Rebase the spec commits on `develop` once #12 is merged; re-read the #12 names used in
  `design.md` (`ScanViewModel`, `ScanPreferences`, `ExpiryField`, `delete` / `undoDelete`)
  and update the design first if any changed. *Check:* `bash scripts/local-ci.sh` green
  before any code.

## 1. Shared rules (#18, #19, #21)

- [x] **T1 — Text and location-name fixtures** `[shared]` [#18, #19] (S1, S2, L2, L3, L11, C4; §2.1, §2.2)
  `fixtures/text-normalization.json`, `fixtures/location-names.json`; error kinds in
  `fixtures/README.md`; broken copies for `test-shared-fixtures-break.sh`.
  *Test:* `check-fixtures.py` green; break script fails on each broken copy.
- [x] **T2 — `TextNormalizer`, `LocationName` in Swift** `[ios]` (same; §1.4, §3.1)
  *Tests first:* `TextNormalizerFixtureTests`, `LocationNameFixtureTests` (one per case);
  grapheme counting with emoji and combining marks. 100 % branch coverage.
- [x] **T3 — Kotlin twins** `[android]` (same)
  `@TestFactory` per case; same error kinds.
- [x] **T4 — Cabinet-rows fixture** `[shared]` [#19] (C1–C5, S1, S3, S4, P1; §2.3)
  `fixtures/cabinet-rows.json` with the cases of §2.3; broken copy. *Test:* `check-fixtures.py`.
- [ ] **T5 — `CabinetRows`, `CabinetFilter`, `ProductRow`, `ExpiryUrgency` in Swift** `[ios]` (same; §1.1, §1.2, §3.2)
  *Tests first:* `CabinetRowsFixtureTests`; perf test 1 000 boxes ≤ 100 ms median of 10
  (§8). 100 % branch coverage.
- [ ] **T6 — Kotlin twins** `[android]` (same)
- [ ] **T7 — Scan-removal fixture** `[shared]` [#21] (R5; §2.4)
  `fixtures/scan-removal.json`; decision names in `fixtures/README.md`; broken copy.
- [ ] **T8 — `RemovalPolicy`, `ScanRemoval` in Swift** `[ios]` [#21] (R5, R9; §3.3)
  *Tests first:* `RemovalPolicyFixtureTests` (pure) and `ScanRemovalTests` (second pass of
  the fixture on the in-memory fake; removal uses the clock's date; undo restores).
  Needs `cabinetBoxes(gtin:)` on the fake (added here, contract check in T11).
- [ ] **T9 — Kotlin twins** `[android]` [#21] (same)
- [ ] **T10 — `BoxEdit` in both cores** `[ios]` `[android]` [#20] (B2; §3.4)
  *Tests first:* trim, 1…200, unknown expiry kept, location change; one commit per platform
  (T10a iOS, T10b Android).

## 2. Schema v3 and repositories (#18, #19)

- [ ] **T11 — Schema v3 fixtures and contract names** `[shared]` (V1, V3, V4; §2.5, §4)
  `fixtures/schema-v3.sql`, `fixtures/schema-v3.json`; new checks appended to
  `repository-contract.json`; repository error names in `fixtures/README.md`; broken copies.
- [ ] **T12 — Core ports, in-memory fake and contract checks** `[ios]` `[android]` (V3, V4, L2–L10, C6; §4.2)
  Port additions and `RepositoryError` cases in both cores; `InMemoryCabinetStore` gains
  location tombstones, moves, reorder, observation (`AsyncThrowingStream` / `Flow`);
  `RepositoryContract` gains the §4.2 checks. *Tests:* contract on the fake, names equal the
  fixture list (T12a iOS, T12b Android).
- [ ] **T13 — iOS v3 migration and GRDB repositories** `[ios]` (V1–V4, C6; §1.3, §1.5, §4)
  `Migrations.v3`; `GRDBLocationRepository` / `GRDBBoxRepository` additions;
  `ValueObservation` streams. *Tests:* `SchemaFixtureTests` v3 (and v2 for a v2-only
  database), `MigrationsTests` v2 → v3 with data, contract on GRDB, bootstrap upgrade test,
  observation perf ≤ 50 ms (§8).
- [ ] **T14 — Android v3 migration and SQL repositories** `[android]` (same; §1.3)
  `Migrations.v3`; `CabinetDatabase.changes`; `SqlLocationRepository` / `SqlBoxRepository`
  additions. *Tests:* `SchemaTest` v3/v2, `MigrationsTest`, `SqlRepositoryTest` contract,
  `BootstrapTest` upgrade, "one emission per transaction", observation perf.

## 3. Locations UI (#18)

- [ ] **T15 — iOS Undo banner** `[ios]` (B5–B7, L7; §1.7)
  `UndoBanner` + `UndoCenter` (one pending action, 10 s, VoiceOver announcement and action,
  no dismissal while focused). *Tests:* view-model/unit tests on `UndoCenter` (replace,
  expire with an injected clock/timer, undo once).
- [ ] **T16 — iOS Locations screen** `[ios]` [#18] (L1–L8, L10, L11; §5)
  `LocationsView`, `LocationsViewModel`, `LocationNameSheet`, `DeleteLocationSheet`; Settings
  row. *Tests:* `LocationsViewModelTests` (create, rename error, reorder, set default, delete
  empty → Undo, delete with boxes → move, default not deletable, failure leaves all
  unchanged); `LocationsUITests` incl. accessibility move actions and largest text size.
- [ ] **T17 — Android Undo snackbar** `[android]` (same as T15)
  `UndoController` + `SnackbarHost` in `MainScaffold`. *Tests:* controller unit tests;
  Robolectric: action shown, undo invoked once.
- [ ] **T18 — Android Locations screen** `[android]` [#18] (as T16; §6)
  `LocationsScreen`, `LocationsViewModel`, dialogs, `ReorderableColumn` (+ unit test of the
  swap logic), accessibility actions; Settings row. *Tests:* `LocationsViewModelTest`,
  Robolectric `LocationsScreenTest` at font scale 2.0.

## 4. Cabinet list (#19)

- [ ] **T19 — iOS Cabinet tab** `[ios]` [#19] (C1–C10, S1–S7, P1–P3; §5) — **blocked by #23**
  `CabinetView`, `CabinetViewModel`, `CabinetFilterBar`, `ProductBoxesView`,
  `ExpiryStatusLabel`; `AppEnvironment.expiryStatus` bound to #23, `categoryOrder = []`;
  `MainTabView` selection for the empty-state button; day-change refresh.
  *Tests:* `CabinetViewModelTests` (fake repos, fixed clock: loading, failure + Retry, empty,
  no results + Clear, counts, deleted-location filter fallback, day change re-evaluates
  status); `CabinetUITests` (seeded cabinet: grouping, search, filters, one-box row → detail,
  largest text size, VoiceOver labels).
- [ ] **T20 — Android Cabinet tab** `[android]` [#19] (as T19; §6) — **blocked by #23**
  `CabinetScreen`, `CabinetViewModel`, filter bar, `ProductBoxesScreen`, routes.
  *Tests:* `CabinetViewModelTest` (Turbine, test dispatcher), Robolectric `CabinetScreenTest`;
  architecture rule (no `persistence` import from `features/cabinet`) + fixture.

## 5. Box detail (#20)

- [ ] **T21 — iOS box detail and edit** `[ios]` [#20] (B1–B3, B5–B8; §5, §7.3, §7.4)
  `BoxDetailView`, `BoxDetailViewModel`, `BoxEditView` (#12 `ExpiryField`). *Tests:*
  view-model (edit/save/cancel, opened toggle, used up / thrown away → Undo restores
  everything, mistake → Undo, `duplicateSerial` message, pops when the box leaves); UI test.
- [ ] **T22 — Android box detail and edit** `[android]` [#20] (as T21)
- [ ] **T23 — Category editing** `[ios]` `[android]` [#20] (B4; §3.4) — **blocked by #10**
  `BoxEdit.category` / `resetCategory()` in both cores (tests first), category picker on both
  platforms, `categoryOrder` bound to the taxonomy. (T23a iOS, T23b Android.)

## 6. Remove by scanning (#21)

- [ ] **T24 — iOS remove mode** `[ios]` [#21] (R1–R4, R6–R9; §1.6, §5, §7.6–7.8)
  `ScanMode`, mode picker, `RemovalReasonChip`, `scan.removalReason` preference,
  `RemovalCandidatesSheet`, removal feedback, session list and summary in remove mode.
  *Tests:* `ScanViewModelTests` (mode resets to Add on appear and launch, reason remembered,
  feedback per outcome, sheet blocks recognitions, Undo per row); `ScanUITests` with injected
  payloads in remove mode; perf `handle` ≤ 100 ms on 1 000 boxes (§8).
- [ ] **T25 — Android remove mode** `[android]` [#21] (as T24; §6)

## 7. Finish

- [ ] **T26 — Accessibility pass** `[ios]` `[android]` (X3)
  VoiceOver / TalkBack walk-through of Cabinet, detail, Locations and remove mode; contrast
  of the remove-mode tint in light and dark mode; fixes + UI test assertions.
- [ ] **T27 — Docs** `[shared]` (X5, X6)
  `docs/cabinet.md`, `docs/locations.md` (new, linked from `docs/README.md`),
  `docs/bulk-scan.md` (remove mode), `docs/architecture.md` (schema v3, observation),
  `fixtures/README.md` complete; note for #26/#27 that the export must carry
  `location.deletedAt` (comment on #27).
- [ ] **T28 — Device measurements** `[shared]` (X2; §8)
  Scroll 1 000 boxes on the oldest available iPhone and Android phone (emulator serial only
  unless the user names a device); remove-by-scan latency; results in
  `reports/cabinet-inventory/README.md`.
- [ ] **T29 — Versions and gate** `[shared]` (X7)
  iOS 0.3.0 (build +1) and Android 0.3.0 (versionCode +1), changelog entries;
  `bash scripts/local-ci.sh` green; coverage stated for the PR.
