# Architecture

MediCabinet is **two native apps** — iOS (Swift, SwiftUI, iOS 17+) in `ios/` and Android (Kotlin,
Jetpack Compose, Android 8.0+) in `android/` — in one repository. The cabinet lives only on the device:
no server, no analytics, no network request. Nothing is shared at the code level; what keeps the two apps
behaving identically is the **shared contract** below.

## The shared contract (`fixtures/`)

Language-neutral test vectors that both test suites run (see [`fixtures/README.md`](../fixtures/README.md)):

| File | Guarantees |
|------|------------|
| `expiry-dates.json` | `ExpiryDate` validation, last valid day, sort key and error kinds are identical |
| `schema-v1.sql`, `schema-v2.sql` | each migration step is **character for character** the same SQL on both platforms |
| `schema-v1.json`, `schema-v2.json` | the migrated databases have the same tables, columns, foreign keys and indexes (v1 = migrated to v1 only, v2 = fully migrated) |
| `gs1-datamatrix.json` | the GS1 / EAN-13 parser returns the same GTIN, expiry (valid, missing or invalid), lot, serial, payload *shape* and error kind |
| `scan-debounce.json`, `scan-intake.json`, `product-resolution.json` | what counts as a new scan, whether a scan is added / asked / refused, and how a product is named |
| `repository-contract.json` | both repository implementations pass the same named list of contract checks |
| `categories.json` | the twelve categories, in display order, are identical in both cores and the reference build |
| `reference-lookup.json` + `reference-sample.sqlite` | GTIN → CIP13 and the product lookup answer identically on both platforms |
| `text-normalization.json`, `location-names.json` | search matching and location-name rules are identical |
| `archive-cases.json`, `archive/v1/golden.medicabinet`, `archive-code-points.json`, `archive-store-contract.json` | the export archive is read, refused and written identically (the golden file is reproduced byte for byte), and every cabinet store passes the same contract; format in [`export-format.md`](export-format.md) |
| `cabinet-rows.json`, `scan-removal.json` | the Cabinet list (grouping, status, order, filters) and removal by scan decide identically |
| `README.md` | the cross-platform names (error kinds, enum raw values) — both suites assert the code matches |

A wrong fixture fails each platform's suite (`scripts/tests/test-shared-fixtures-break.sh`), iOS and
Android French/English wording is compared by `scripts/check-string-parity.py` (the Android strings started as
a copy of the iOS catalog). The export archive (and the future sync payload) is specified once and tested
the same way.

## Layers (both platforms)

```
View/Screen  →  ViewModel  →  repository interface (core)  →  SQLite implementation
```

| | iOS (`ios/`) | Android (`android/`) |
|--|--------------|----------------------|
| Domain core, pure and testable on the desktop | Swift package `MediCabinetCore` (`swift test`) | Gradle module `:core`, Kotlin/JVM (`./gradlew :core:test`) |
| Core may not import | UIKit, SwiftUI, VisionKit, GRDB; never `Date()` | `android.*`, `androidx.*`; never the wall clock outside `SystemClock` |
| Test helpers | `MediCabinetCoreTestSupport` (fake store, `RepositoryContract`) | `:core` test fixtures (same) |
| Persistence | GRDB — the only place that imports it is `Persistence/` | `androidx.sqlite` — only under `persistence/` |
| App | `MediCabinet/Sources/{App,Features,Persistence}` | `app/src/main/kotlin/com/medicabinet/app/{app,features,persistence,ui}` |
| Dependency injection | manual (`AppEnvironment`, built by `Bootstrap`) | manual (`MediCabinetApplication` builds `Bootstrap`) |

The architecture rules are enforced by lint: SwiftLint custom rules (`ios/.swiftlint.yml`) and
`scripts/android-check-architecture.py`, each with fixtures proving the rules fire.

## The database

SQLite on both platforms, running the **same SQL** (v1, then v2), in the app's private storage:

- iOS: GRDB, `Application Support/MediCabinet/cabinet.sqlite`, Data Protection
  `completeUntilFirstUserAuthentication`, included in iCloud/iPhone backups.
- Android: `androidx.sqlite`, `databases/cabinet.db` in internal storage (encrypted at rest by the device),
  included in Android Auto Backup and device transfer together with its `-wal` file and the backup copies.
- WAL mode, foreign keys on. Ids are UUIDs stored as upper-case text; every row has `createdAt` /
  `updatedAt`; dates are UTC text `yyyy-MM-dd HH:mm:ss.SSS`. Boxes are never deleted when removed:
  `removalReason` + `removedAt` make Undo possible. The model is sync-ready (see CLAUDE.md, Product decisions).
- Expiry is stored as year/month/day parts plus `expirySortKey`. A box with no expiry has all of them `NULL`
  and is *unknown*, never OK. Month precision (GS1 day `00`) leaves `expiryDay` `NULL`.
- SQL constraints back up the Kotlin/Swift rules (lengths, 14-digit GTIN, month range, one default
  location, removal columns set together, no deleting a location that holds boxes, one box per
  GTIN + serial among boxes that are not deleted).
- **Removed vs deleted.** *Removed* (used up / thrown away) is a stock movement: the box stays visible to Undo.
  *Deleted* (`deletedAt`, schema v2) is a box added by mistake, kept as a tombstone for the future sync and
  export but invisible to every query; the scan session's delete and Undo use it.

### Migrations

Named, ordered, append-only (`Migrations.swift` / `Migrations.kt`): never edit one that has shipped, add a
new one **on both platforms** (and add its `fixtures/schema-vN.sql`/`.json`). v2 adds `box.deletedAt` and makes
`box_serial` unique among non-deleted boxes; opening a v1 file backs it up first, and a v1 file that holds
duplicate serials fails the migration, is restored and keeps working on v1. iOS tracks them in
GRDB's table, Android in `PRAGMA user_version`. Neither platform ever erases the database on a schema change;
Android refuses a downgrade.

### Schema versions

| Version | Adds | Fixtures |
|---------|------|----------|
| v1 | `location`, `box`, their indexes | `schema-v1.sql`, `schema-v1.json` |
| v2 | `box.deletedAt` (a box added by mistake), unique serial among boxes that are not deleted | `schema-v2.sql`, `schema-v2.json` |
| v3 | `location.deletedAt` (a location the user deleted; removed and deleted boxes keep pointing at it) | `schema-v3.sql`, `schema-v3.json` |

`*.sql` is the migration step both platforms run (compared as text); `*.json` is the full schema a freshly
migrated database must have (compared by introspection).

### Observing changes

Screens follow the data instead of refreshing it. The repositories expose `observeAll()` (locations) and
`observeCabinet()` (boxes in the cabinet): they emit the current value, then again after every change.

- iOS: GRDB `ValueObservation`, wrapped in an `AsyncThrowingStream`; GRDB stays inside `Persistence/`.
- Android: `CabinetDatabase.changes` is a counter bumped after every **committed** `transaction {}` (a rollback
  or a read does not bump it); the repositories re-query on each bump, off the main thread, conflated. All writes
  must therefore go through `transaction`.

On Android each port is split into role interfaces (`LocationEditing`, `BoxLookups`) that the repository
interfaces extend, to keep each interface small; the apps still use `LocationRepository` / `BoxRepository`.

### Backups and failure handling

Before applying pending migrations to an **existing** database, a `BackupService` writes a self-contained copy
(`Backups/pre-migration-<UTC time>-<last applied>.sqlite` on iOS, `backups/…db` on Android) using SQLite's
backup API / a checkpointed copy, and keeps the newest five. `Bootstrap` runs on launch, off the main thread:

1. Open the database (create the protected directory on first launch).
2. If migrations are pending on an existing database: back it up. If that fails → stop, "Backup failed"
   with **Try again**. Nothing is migrated.
3. Migrate in one transaction. If it fails → put the pre-migration copy back, "Update failed" with
   **Try again**. The copy stays in the backups folder.
4. Ensure exactly one default location exists (localized name).
5. Prune old backups, hand the environment to the UI.

On a first launch there is nothing to lose, so no backup is made. **An unreadable database file is reported
("Storage unavailable") and never deleted** — Android's platform helpers delete corrupt databases by default,
so `CabinetDatabase` overrides that, with a mutation-checked regression test; iOS has the same guarantee.

### Archive layer and automatic backups

The export archive (encode, decode, validate, migrate) and `CabinetTransfer` live in both cores; the apps add
`CabinetArchiveStore` (`GRDBCabinetArchiveStore` / `SqlCabinetArchiveStore`: one read for a snapshot, one write
transaction for a replace) and `ArchiveBackupStore` (`FileArchiveBackupStore`). The replace transaction writes and
verifies the automatic backup (`pre-import-*` / `pre-restore-*.medicabinet`, newest 3 kept) in the same folder as
the pre-migration copies, without touching them. After a replace the app bumps a **cabinet revision**
(`CabinetRevision` on iOS, `StateFlow<Long>` on Android); screens that cache cabinet data reload and the scan
session ends. See [export-import.md](export-import.md) and [export-format.md](export-format.md).

## The reference database

A second, **read-only** SQLite database ships inside each app: the BDPM names and categories by CIP13
(`reference-data/bundle/reference.sqlite`, ~1.7 MB, the same file for both apps; contents, rules and the update
procedure are in [reference-data.md](reference-data.md)). It is separate from the cabinet database, holds no
household data, and is never migrated: a new data release replaces the whole file.

- The cores declare the port (`ProductLookup`, `ReferenceDataSource`), the taxonomy and the GTIN → CIP13 rule;
  `Persistence/Reference/` (iOS, GRDB) and `persistence/reference/` (Android) implement it. Views never open it.
- It is opened **lazily**, on the first lookup or when About asks for its date, off the main thread, and never during
  bootstrap, so cold start is unchanged. Missing, unreadable or unsupported data means every product is unknown; the
  cabinet is unaffected.
- iOS opens the file in the signed app bundle. Android copies it from the APK assets to `noBackupFilesDir/reference/`
  (internal storage that Auto Backup and device transfer never include), atomically and verified against the
  manifest's `contentSha256`; a marker file naming the installed version is written last.
- Classification happens when the file is **built**, not in the apps.

## The scan path

See [`bulk-scan.md`](bulk-scan.md). The camera sits behind a `CodeScanner` protocol/interface and is the only
place that knows VisionKit (iOS) or CameraX + zxing-cpp (Android) — a lint rule on each platform enforces it. Frames
become payload strings; everything after that (GS1 parsing, debouncing, intake decision, product resolution) is
pure code in the two cores, tested by the shared fixtures.

## Notifications

The monthly reminder is local only (see [`expiry-tracking.md`](expiry-tracking.md)): no push server, no network. The
digest content and its dates come from one pure function in each core (`ExpiryDigest`), proven by
`fixtures/expiry-digest.json`; only the scheduling differs. iOS schedules the next 12 calendar notifications (the
system caps pending ones at 64) and `UserNotifications` is imported only under `App/Reminders/`. Android arms one
inexact `AlarmManager` alarm and its receiver computes the counts at fire time; `AlarmManager` and the notification
APIs are used only under `reminders/`. Preferences of the feature are device settings (`UserDefaults` /
`SharedPreferences`), not cabinet data.

## Privacy

No analytics, crash reporting, advertising or network code (lint rules on both platforms; the Android manifest
requests the camera, notification (`POST_NOTIFICATIONS`) and boot (`RECEIVE_BOOT_COMPLETED`, to re-arm the reminder)
permissions only — in particular no `INTERNET` and no exact-alarm permission). iOS `PrivacyInfo.xcprivacy` declares no tracking, no collected data and the `UserDefaults` required-reason API (`CA92.1`, the scanner's, expiry window's and reminder's UI preferences). Camera frames are never stored or sent. Logs carry
no cabinet contents: iOS uses `os.Logger`'s private default (public interpolation is lint-forbidden), Android
logs only the failure stage and exception class through `AppLog`.

## Localization

French (fallback) and English on both platforms: iOS String Catalogs, Android `values/` (French) and
`values-en/`, with per-app language selection on Android 13+. The same screen says the same thing on both.
