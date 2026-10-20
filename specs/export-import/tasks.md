# Export and import of the cabinet — Tasks

Status: **Approved**
Epic: #26 · Branch: `feature/26-export-import`

Wording of every new string: [`strings.md`](strings.md) (both platforms use it as is).

One task = one focused commit (code + tests + this file checked off). Tags: `[shared]`,
`[ios]`, `[android]`. Child issue in brackets; requirement IDs refer to
[`requirements.md`](requirements.md), sections (§) to [`design.md`](design.md).

Order: rebase on the merged bulk-scan epic → shared fixtures → JSON primitives and archive
codec in both cores → ports and the transfer use case → persistence → UI per platform →
device checks, docs, measurements, version. **Nothing starts before T0** (decision 1 of the
requirements). The iOS UI block (T11–T16) and the Android UI block (T17–T22) are
independent of each other.

## 0. Prerequisite

- [x] **T0 — Rebase on `develop` after #12** `[shared]` (decision 1; §14)
  Done 2026-10-20: the branch is fast-forwarded to `develop` (#12, #17, #22 and the first slice of this epic
  merged). Schema is v3 (`box.deletedAt`, unique live serial, `location.deletedAt`), exactly what §2.2
  assumes; no other schema change landed. #26, #28, #29 marked in progress.

## 1. Archive format in both cores (#27)

- [x] **T1 — Archive fixtures** `[shared]` [#27] (F2–F10, V2–V3; §2, §3)
  `fixtures/archive/v1/golden.medicabinet` (every variant of §3, canonical form of §2.3),
  `fixtures/archive-cases.json` (golden summary, every error kind with its path, hostile
  inline / base64 / generated cases), `fixtures/archive-code-points.json`. Error kinds,
  platform values, MIME and UTType in `fixtures/README.md`. `check-fixtures.py` validates
  `fixtures/archive/**/*.medicabinet` (+ self-test case). Broken copies for
  `test-shared-fixtures-break.sh`. *Test:* `check-fixtures.py` green, its self-test fails on
  the broken copies.
- [x] **T2 — Strict JSON reader, canonical writer, dates, code points (Swift)** `[ios]` [#27] (V4, F8; §1.1, §2.3, §2.4)
  Internal `JSONReader` (pull tokenizer: UTF-8 checks, BOM, depth, string bytes, Int64,
  duplicate keys, trailing data), `CanonicalJSONWriter`, `ArchiveDates`, `CodePoints` in
  `MediCabinetCore/Archive/`. *Tests first:* reader and writer unit tests,
  `CodePointsFixtureTests`, reader fuzz (seeded random, truncations), round trip
  writer → reader. 100 % branch coverage.
- [x] **T3 — Kotlin twin of T2 in `:core`** `[android]` [#27] (same)
  The same tests (`@TestFactory` per code-point case, fuzz). No new dependency.
- [x] **T4 — Archive model, validator, encoder, decoder, v1 reader (Swift)** `[ios]` [#27] (F1–F10, V1–V5; §2, §4)
  `ArchiveFormat`, `ArchiveLimits`, `ArchivedBox`, `CabinetContents` (`summary`,
  `needsBackup`), `Archive`, `ArchiveError` / kinds, `ArchiveValidator`, `ArchiveEncoder`
  (validates its own output), `ArchiveDecoder`, `ArchiveV1Reader`, `ArchiveMigrations`.
  *Tests first:* `ArchiveFixtureTests` (one per case), the canonical byte-for-byte golden
  test, golden-per-version existence test, property test (random valid cabinets
  round-trip), `ArchiveFuzzTests` (truncations and mutations of the golden file), "error
  description holds no value", core performance (§9: encode / decode 1 000 boxes, maximum
  file). Break script covers the new fixtures. 100 % branch coverage.
- [x] **T5 — Kotlin twin of T4** `[android]` [#27] (same)
  The same tests and budgets; break script.

## 2. Ports and the transfer use case (#29)

- [x] **T6 — Store contract names** `[shared]` [#29] (I4–I6; §5.1)
  `fixtures/archive-store-contract.json` (its own file, so it does not collide with the bulk-scan edits of
  `repository-contract.json`). *Test:* `check-fixtures.py` green.
- [x] **T7 — Ports, fakes and `CabinetTransfer` (Swift)** `[ios]` [#29] (I1–I9, B1–B3, B5; §4.1, §4.2)
  `CabinetArchiveStore`, `ArchiveBackupStore`, `BackupReason`, `ArchiveBackupEntry`;
  in-memory fakes and `RepositoryContract` store checks (on the fake) in
  `MediCabinetCoreTestSupport`; `CabinetTransfer`, `ImportPreview`, `ImportResult`,
  `TransferError`, `fileName(for:in:)`. *Tests first:* `CabinetTransferTests` (preview
  content, older version kept as source version, nothing to lose → no backup, hook failure →
  unchanged + `backupFailed`, prune after commit only, prune failure ignored, file name at
  day boundaries and in time zones).
- [x] **T8 — Kotlin twin of T7** `[android]` [#29] (same)
  The same tests on `:core` test fixtures (Turbine / coroutines-test where needed).

## 3. Persistence (#29)

- [ ] **T9 — iOS store and backup files** `[ios]` [#29] (F7, I4–I6, B1–B2, B4, B6; §1.2, §1.3, §5)
  `GRDBCabinetArchiveStore`, `FileArchiveBackupStore` (names, `.partial` → decode → rename,
  protection class, unreadable entries, keep 3 over both prefixes). *Tests:* contract checks
  on GRDB; integration round trip (every field variant → export → apply on a fresh database
  → equal snapshot); rollback when an insert fails; backup-store tests (pre-migration files
  untouched); performance `exportArchive` / `apply` ≤ 600 ms (§9).
- [x] **T10 — Android store and backup files** `[android]` [#29] (same)
  `SqlCabinetArchiveStore`, `FileArchiveBackupStore` in `persistence/`; the same tests
  (Robolectric SQLite), including the performance budgets.
  Done: measured (best of 5, Robolectric SQLite, 1 000 boxes) `exportArchive` ≈ 27 ms, `apply` with its backup
  ≈ 390 ms (budget 600 ms). The store also refuses two live boxes with the same GTIN and serial
  (`duplicateSerial`) before the hook, so no backup file is written for a cabinet the database would refuse.

## 4. iOS app (#28, #29)

- [ ] **T11 — Environment, revision and scan session reset** `[ios]` (I7; §1.6, §6)
  `AppEnvironment.transfer` and `revision`, built in `Bootstrap` (`ArchiveApp` from
  `BundleAppInfo`); launch cleanup of `tmp/Export` and `Documents/Inbox`; the scan session
  ends on a revision change. *Tests:* bootstrap builds the environment; cleanup;
  scan view-model reset.
- [ ] **T12 — Export** `[ios]` [#28] (E1–E9, X1, X4; §6, flow 1)
  `TransferSection` in Settings, `ExportViewModel`, `ExportSummaryView` (counts, warning,
  empty cabinet), `ActivityView` with temp-file deletion, error + **Try again**. Strings
  (fr + en). *Tests:* view-model states (cancel exports nothing, failure → retry, temp
  deleted on completion); UI test: the warning is shown before the share sheet.
- [ ] **T13 — Document type, open-with and coordinator** `[ios]` [#28, #29] (O1, O4, O5; §1.4, §6)
  `UTType+Archive`, `project.yml` Info.plist keys (`UTExportedTypeDeclarations`,
  `CFBundleDocumentTypes`, `LSSupportsOpeningDocumentsInPlace = NO`), localized type name;
  `.onOpenURL` → `ImportCoordinator` (pending while loading, refused when failed, waits while
  busy + banner, camera stopped under the cover, Inbox copy deleted at the end); busy
  registration in the scan completion step, manual entry and row editor; launch argument
  `-UITestingOpenArchive <fixture>`. *Tests:* coordinator unit tests; Info.plist key test;
  UI test opening the golden file.
- [ ] **T14 — Import flow** `[ios]` [#29] (I1–I10, V5, O3; §6, flows 3–6, 8)
  `.fileImporter`, capped read, `ImportViewModel` (staged progress), `ImportPreviewView`
  (file vs phone, replace and backup statements, nothing to lose), destructive button +
  confirmation dialog, `ImportResultView`, error screens per kind. *Tests:* view-model per
  state and kind; UI tests: preview → confirm → result shows the imported cabinet; Cancel
  changes nothing; newer-version file shows "update MediCabinet".
- [ ] **T15 — Automatic backups and restore** `[ios]` [#29] (B4, B5; §6, flow 7)
  `BackupsView` (newest first, reason, count, unreadable row, empty state) → restore through
  the import flow (`beforeRestore`). *Tests:* view-model; UI test: import then restore
  brings the first cabinet back.
- [ ] **T16 — iOS accessibility pass** `[ios]` (X3, X4)
  Accessibility text sizes, dark mode, VoiceOver labels and announcements for progress and
  results, 44 pt targets, statuses not by colour alone. *Tests:* UI tests at accessibility
  sizes.

## 5. Android app (#28, #29)

- [x] **T17 — Environment, revision and scan session reset** `[android]` (I7; §1.6, §7.4)
  `AppEnvironment.transfer` and `revision`, `ArchiveApp` from `PackageAppInfo`; launch
  cleanup of `cache/export`; the scan session ends on a revision change. *Tests:* as T11.
  Done: the scan view model checks the revision each time the Scan tab loads (the tab is never on screen during an
  import, which runs from Settings), so it needs no coroutine of its own.
- [x] **T18 — Export: save and share** `[android]` [#28] (E1–E9, X1; §7.1, flows 1–2)
  `TransferSection`, `ExportViewModel`, `ExportSummarySheet`; **Save to file**
  (`CreateDocument`, delete on failure) and **Share** (`FileProvider`, `export_paths.xml`,
  chooser); architecture rule + fixture (§7.4). Strings (fr + en, same wording as iOS).
  *Tests:* view-model (Turbine); Robolectric: CreateDocument result → bytes written, failure
  → document deleted, share intent grants read on the provider URI only; manifest test
  (provider not exported); `PrivacyTest` unchanged.
  Done: the summary sheet offers **Save to file** and **Share** directly (Android has no
  separate Continue step; `transfer_export_continue` exists for parity only). The provider
  authority is `${applicationId}.export` (= `com.medicabinet.app.export` in the shipped app),
  so the side-by-side `.perf` build can be installed too (design §7.1 amended).
- [x] **T19 — Open-with filters and coordinator** `[android]` [#28, #29] (O2, O4, O5; §7.3)
  The §7.3 intent filters, `singleTop`, `onNewIntent`, `ImportCoordinator` (immediate capped
  read, gating like iOS); busy registration in the scan sheets. *Tests:* manifest test (the
  filters exactly, no JSON, no `*/*`); Robolectric `ACTION_VIEW` / `ACTION_SEND` intents;
  coordinator unit tests.
  Done: only `content:` addresses are read (the activity is exported: a `file:` address could
  name the app's own files); anything else is refused as unreadable. A file that cannot be
  opened at launch (storage unavailable) is refused in a dialog over the launch error.
- [ ] **T20 — Import flow** `[android]` [#29] (I1–I10, V5, O3; §7.2, flows 3–6, 8)
  `OpenDocument`, `ImportViewModel`, `ImportPreviewScreen`, confirmation `AlertDialog`,
  `ImportResultScreen`, error screens, the "file not offered?" hint. *Tests:* as T14
  (Robolectric Compose).
- [ ] **T21 — Automatic backups and restore** `[android]` [#29] (B4, B5)
  `BackupsScreen` → restore flow. *Tests:* as T15.
- [ ] **T22 — Android accessibility pass** `[android]` (X3, X4)
  Font scale 2.0, dark theme, TalkBack labels and live regions, 48 dp targets.

## 6. Finish

- [ ] **T23 — Open-with device check** `[shared]` [#28] (O1, O2; §7.3, open point 2)
  **Needs the user:** on an iPhone (Files, Mail) and on the Android device the user names
  (`ANDROID_SERIAL`, never an unnamed device) plus the emulator (Files / Downloads, Gmail,
  Quick Share or Bluetooth). Results in `reports/export-import/README.md`. If the filters
  must change, update the design first.
- [ ] **T24 — Cross-platform round trip on devices** `[shared]` (F7, X7)
  **Needs the user:** export on iPhone → import on Android → export → import on iPhone;
  compare the summaries; result in the report.
- [ ] **T25 — Docs** `[shared]` (X6)
  `docs/export-format.md`, `docs/export-import.md` (+ `docs/README.md` link),
  `docs/architecture.md` (archive layer, backups folder, cabinet revision),
  `fixtures/README.md` final; string parity green.
- [ ] **T26 — Measurements** `[shared]` (X2; §9)
  Export / import of a 1 000-box cabinet on an iPhone and on the named Android device,
  recorded in `reports/export-import/README.md` with the test budgets.
- [ ] **T27 — Versions and gate** `[shared]` (X8)
  The next MINOR on both apps (read the current versions from the source of truth;
  expected iOS 0.5.0 build 6, Android 0.5.0 versionCode 6: the branch is based on 0.4.0, build 5), changelog entries;
  `bash scripts/local-ci.sh` green. Coverage stated in the PR.
