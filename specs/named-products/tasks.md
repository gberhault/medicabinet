# Products you named — Tasks

Status: **Pre-approved 2026-10-20** (implementation waits for the user's go)
Requirements: [`requirements.md`](requirements.md) · Design: [`design.md`](design.md) · Issue: #57

One task = one commit (code + tests + docs it changes + this file ticked). Every commit builds and passes the unit
tests of each product it touches. A shared fixture change lands on **both** cores in the same commit.

Before T1: PR #55 (export-import epic) has merged; rebase this branch onto `origin/develop`.

## Schema and port (shared, both apps)

- [ ] **T1** `[shared]` `[ios]` `[android]` **Schema v4.** Add `fixtures/schema-v4.sql` / `.json` (design §2.1) and
  their README rows. Append the v4 migration on iOS (`Migrations.swift`) and Android (`Migrations.kt`). Update the
  schema fixture tests (table set, v4 snapshot, SQL equality). Add `MigrationV4Tests` / `SchemaV4Test`: a v3 cabinet
  upgrades keeping every row; the table is empty; the marker is `pending`; step-by-step equals fresh install.
  Update `docs/architecture.md`. *(N1, X1)*
- [ ] **T2** `[shared]` `[ios]` `[android]` **Model, port, fakes, contract.** `NamedProduct`, `NamedProductSummary`,
  `NamedProductRepository` (Kotlin: `NamedProductEditing` + `NamedProductRepository`), and
  `BoxRepository.latestBoxPerGTIN()`. In-memory fakes on both cores. The named-product checks appended to
  `fixtures/repository-contract.json` and implemented in both `RepositoryContract`s, run against the fakes.
  *(N1, E3, E4, F2, F3, S2)*
- [ ] **T3** `[ios]` **GRDB implementation.** `GRDBNamedProductRepository` and `latestBoxPerGTIN` in
  `GRDBBoxRepository`, passing the contract (`GRDBRepositoryTests`). Add a performance test of `observeSummaries` with
  1 000 products and 1 000 boxes. *(S7, E3, E4)*
- [ ] **T4** `[android]` **SQL implementation.** `SqlNamedProductRepository` and `latestBoxPerGTIN` in
  `SqlBoxLookups`, passing the contract (`SqlRepositoryTest`), with the same performance test. *(S7, E3, E4)*

## Core rules (both cores)

- [ ] **T5** `[shared]` `[ios]` `[android]` **Resolution from named products.** Rewrite and extend
  `fixtures/product-resolution.json` (design §3.1) and change `ProductResolver.resolve(reference:named:previousBox:)`
  on both cores. Add `ProductMemory.resolve`. `ScanIntake` takes a `ProductMemory`. Replace
  `theNameOfAnEarlierBoxIsReused…` with the named-product tests, and add the regression test: a used-up box with a
  test name no longer names the next scan (`// Regression #57`). Update the `scan-intake.json` description and
  `docs/bulk-scan.md`. Wire `ProductMemory` and `named` into both `AppEnvironment`s and bootstraps so the apps build.
  *(N3)*
- [ ] **T6** `[shared]` `[ios]` `[android]` **When a save remembers.** Add `fixtures/product-memory.json`,
  `MemoryPolicy`, and `ProductMemory.record`, with fixture-driven tests and `ProductMemoryTests` (unavailable
  reference, throwing lookup, failing write). `ScanIntake.add` records `.added`. *(N2, N5–N8)*
- [ ] **T7** `[shared]` `[ios]` `[android]` **`NamedProductEdit`.** Both cores, with mirrored unit tests (trim,
  empty, 200 / 201 code points, category not selectable, reset, only-what-changed). *(E1, E2)*
- [ ] **T8** `[shared]` `[ios]` `[android]` **Backfill rule and runner.** Add `fixtures/named-product-backfill.json`,
  `NamedProductBackfill`, and `NamedProductBackfillRunner` on both cores, with fixture tests and runner tests
  (design §11 B2). Extend `scripts/tests/test-shared-fixtures-break.sh` with broken `product-memory` and
  `named-product-backfill` cases. *(B1, B2, B4)*

## App paths that record (each platform)

- [ ] **T9** `[ios]` **Record from session corrections and box edits, run the backfill.** `ScanViewModel+Session.update`
  and `BoxEditViewModel.save` call `productMemory.record`. The backfill runner starts after bootstrap. VM tests:
  a name change records; an expiry-only change does not; a record failure leaves the save successful. Bootstrap test:
  the runner runs once and clears the marker with the in-memory reference. *(N4, N5, B2)*
- [ ] **T10** `[android]` **Same on Android.** `ScanViewModel.update`, `BoxDetailViewModel.save`, and the runner in
  `MediCabinetApplication` on the injected dispatcher. Same tests. *(N4, N5, B2)*

## Archive version 2 (shared, both apps)

- [ ] **T11** `[shared]` `[ios]` `[android]` **Format v2 in the cores.** Add `fixtures/archive/v2/golden.medicabinet`
  and the v2 cases in `archive-cases.json` (design §7.2). Add the `namedProducts: 0` expectation to the v1 cases and
  the `duplicateNamedProduct` kind to `fixtures/README.md`. Add `ArchivedNamedProduct`, the `CabinetContents` /
  `CabinetSummary` fields, `ArchiveV2Reader`, the encoder at v2, the validator rules and
  `ArchiveLimits.maxNamedProducts`, on both cores. The v1 golden still decodes; the v2 golden round-trips byte for
  byte. Update `docs/export-format.md`. *(A1–A3, A5)*
- [ ] **T12** `[shared]` `[ios]` `[android]` **Stores and import.** Extend `fixtures/archive-store-contract.json`.
  `GRDBCabinetArchiveStore` / `SqlCabinetArchiveStore` snapshot and replace named products and write the marker.
  After an import, the app runs the backfill runner. `CabinetTransferTests` on both cores: a v1 import sets the
  marker and the runner fills from the imported boxes. The import preview shows the named-product count
  (`transfer.preview.namedProducts`, fr + en, both apps). *(A2, A4, B3)*

## Settings screen

- [ ] **T13** `[ios]` **Shared category picker.** Extract `BoxEditView`'s category section into
  `Features/Common/CategoryPickerSection`, used by box edit. The existing box-edit tests stay green unchanged.
  *(E1)*
- [ ] **T14** `[ios]` **View model.** `NamedProductsViewModel` (design §6.2) with unit tests: phases, order, counts,
  follows changes, save (rename only, category only, both, invalid, failure), forget message with 0 / 1 / 2 boxes,
  cancel, confirm, undo, undo refused. *(S2–S4, E1–E6, F1–F5)*
- [ ] **T15** `[ios]` **Screens, strings, UI tests.** `NamedProductsEntry`, `NamedProductsView`,
  `NamedProductEditSheet`, the forget dialog, the Settings row, the strings of design §6.1 (fr + en; first check
  which reused keys exist on both platforms and list them here), and the `UITestSeed.namedProducts` seed.
  `NamedProductsUITests`: Settings row → list; empty state; rename shows in the Cabinet tab; forget + Undo above the
  tab bar; accessibility actions; ≥ 44 pt; largest text size + dark mode. Run `ios-check-strings.sh`.
  *(S1–S6, E1, E5, F1, F3, X4)*
- [ ] **T16** `[android]` **Shared category field.** Extract `BoxEditSheet`'s `CategoryField` into
  `features/common/CategoryField.kt`. The existing tests stay green. *(E1)*
- [ ] **T17** `[android]` **View model.** `NamedProductsViewModel` with the same cases as T14 (Turbine). *(S2–S4,
  E1–E6, F1–F5)*
- [ ] **T18** `[android]` **Screens, strings, UI tests.** The route, `NamedProductsScreen`, `NamedProductEditDialog`,
  `ForgetNamedProductDialog`, the Settings row, the strings (`values` + `values-en`), and `namedProducts_more` in
  `ANDROID_ONLY`. `NamedProductsScreenTest` (rows, empty, error + retry, font scale 2, French plurals, ≥ 48 dp,
  custom actions) and `NamedProductsNavigationTest` (Settings → list → back; forget + Undo above the navigation bar).
  Run the Android string checks and `check-string-parity.py`. *(S1–S6, E1, E5, F1, F3, X4)*

## Docs and release

- [ ] **T19** `[shared]` **Docs.** `docs/named-products.md` (new) and its `docs/README.md` row. Check
  `docs/bulk-scan.md`, `docs/export-format.md` and `docs/architecture.md`, which were updated in their tasks.
  *(X5)*
- [ ] **T20** `[ios]` `[android]` **Versions.** Minor bump of both apps with changelog entries;
  `check-version.sh ios` and `android`. If the leaflet-link branch (#56) merged first, take the next minor after
  it. *(X6)*
- [ ] **T21** `[shared]` **Gate.** `bash scripts/local-ci.sh`: full run, targeted reruns of failed steps, one final
  full run. State coverage and the performance numbers (design §10) in the PR. *(X7)*
