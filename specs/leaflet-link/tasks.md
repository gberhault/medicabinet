# Official leaflet link — Tasks

Status: **Pre-approved 2026-10-20** (implementation waits for the user's go)
Requirements: [`requirements.md`](requirements.md) · Design: [`design.md`](design.md) · Issue: #56

One task = one commit (code + tests + docs it changes + this file ticked). Every commit builds and passes the unit
tests of each product it touches. The reference schema moves in one commit on the script **and** both readers,
because the shared sample database would otherwise break the app suites.

**Single branch (user decision 2026-10-20).** This spec and `specs/named-products/` ship from one branch,
`feature/56-57-leaflet-and-named-products`, in one worktree. This spec goes **first**: it needs nothing from the
export-import epic (PR #55). Both specs touch `ReferenceProduct`, `BoxDetailViewModel` and `AppEnvironment`, so the
order avoids conflicts. Rebase onto `origin/develop` (never merge it) whenever develop has moved.

## Reference data (shared, both apps)

- [x] **T1** `[shared]` `[ios]` `[android]` **Reference schema v2 with the CIS.**
  - `build-reference-db.py`: everything in design §2.2.
  - `test-build-reference-db.py`: five columns; CIS checks (7 digits, a letter, null on a current row refused; null
    on a withdrawn row accepted); `user_version`/manifest 2; build against a v1 previous database succeeds and
    carries no CIS onto withdrawn rows; another version still fails; a changed CIS changes `contentSha256`.
  - Regenerate `fixtures/reference-sample.sqlite` + `reference-sample-manifest.json`; add `cis` and `isWithdrawn` to
    `fixtures/reference-lookup.json` and to the `CommittedDataTests` comparison.
  - `ReferenceProduct` gains `cis` / `isWithdrawn` with defaults (both cores).
  - iOS `ReferenceDatabase` and Android `ReferenceDatabase` accept v2, select `cis` and `withdrawnSince`. Android
    `contentSha256()` gets the new row line.
  - The fixture loops (`ReferenceDataTests.swift`, `SqlReferenceDataTest.kt`) check the new fields; the
    `unsupportedSchema` tests use `user_version = 3`.
  - `docs/reference-data.md` schema block and checksum line; `fixtures/README.md`; the B2 amendment note in
    `specs/reference-data/requirements.md`.
  - **The committed bundle is rebuilt in the same commit**, because both apps refuse a v1 bundle once they read v2.
    The v1 bundle holds no CIS, and the 2026-10-18 source files are not kept (the procedure downloads to a temporary
    directory), so the rebuild is also a **data update**: `download` → `build --data-date <latest publication
    date>` → `verify --apps`. Read the report, put it in the commit body, and update the counts and date in
    `docs/reference-data.md`. Check by hand that `…/medicament/<CIS>/extrait` opens for two CIS from the new bundle,
    and add that manual check to the update procedure in `docs/reference-data.md`.
  - Run: the script tests, `check-fixtures.py`, `swift test`, `:core:test`, iOS `ReferenceDataTests`, Android
    `SqlReferenceDataTest` + `ReferenceInstallerTest`. *(R1–R6, X5)*

## Shared rule (both cores)

- [x] **T2** `[shared]` `[ios]` `[android]` **`LeafletLink`.** Add `fixtures/leaflet-links.json` (the cases in design
  §3.2) and its README row. Add `LeafletLink` to `MediCabinetCore` and Kotlin `:core`, with
  `LeafletLinkTests` / `LeafletLinkTest` running every case through `SharedFixtures`. Extend
  `scripts/tests/test-shared-fixtures-break.sh` with a broken `leaflet-links` case. Run `check-fixtures.py`,
  `swift test`, `:core:test` and the break test. *(L2, L3)*

## iOS

- [ ] **T3** `[ios]` **View model.** `BoxDetailViewModel.leaflet` and `openLeafletFailed()` (design §4.2). Unit tests:
  known GTIN → URL; unknown, withdrawn, no GTIN, throwing lookup → `nil`; an update of the same box does not look up
  again (counting `ProductLookup` fake); the failure message is shown. *(L1, L3, L4, L5)*
- [ ] **T4** `[ios]` **View and strings.** The link section (design §4.3) and the four strings (fr + en). Extend
  `FixedProductLookup` / the UI-test seed so a seeded GTIN has a CIS. UI tests: the link exists with its label, hint
  and link trait, ≥ 44 pt, below the information; absent for a box without a code; largest accessibility text size.
  Update `docs/cabinet.md` (Box detail). Run `ios-check-strings.sh`. *(L1, L6, L7, X4, X5)*

## Android

- [ ] **T5** `[android]` **View model.** `BoxDetailViewModel.leaflet` (design §5.2). Turbine tests: the same cases as
  T3, plus cancellation is not swallowed. *(L1, L3, L4)*
- [ ] **T6** `[android]` **Screen and strings.** The link row and note (design §5.3) and the four strings
  (`values` + `values-en`). Robolectric tests: shown with label, click label and ≥ 48 dp; a click hands the fixture
  address to a fake `UriHandler`; a throwing handler shows the message; absent without a code; font scale 2. Run the
  Android string checks and `check-string-parity.py`. *(L1, L2, L5, L6, L7, X4)*

## Release

- [ ] **T7** `[ios]` `[android]` *(done once for the whole branch: see `specs/named-products/tasks.md` T20, which
  bumps the minor version once with one changelog entry per app covering both features, and T21, the single gate
  run.)*
