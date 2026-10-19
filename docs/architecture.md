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
| `schema-v1.sql` | the v1 database DDL is **character for character** the same SQL on both platforms |
| `schema-v1.json` | the migrated databases have the same tables, columns, foreign keys and indexes |
| `repository-contract.json` | both repository implementations pass the same named list of contract checks |
| `categories.json` | the twelve categories, in display order, are identical in both cores and the reference build |
| `reference-lookup.json` + `reference-sample.sqlite` | GTIN → CIP13 and the product lookup answer identically on both platforms |
| `README.md` | the cross-platform names (error kinds, enum raw values) — both suites assert the code matches |

A wrong fixture fails each platform's suite (`scripts/tests/test-shared-fixtures-break.sh`), iOS and
Android French/English wording is compared by `scripts/check-string-parity.py` (the Android strings started as
a copy of the iOS catalog). The future export format and sync payload will be specified once and tested
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

SQLite on both platforms, running the **same v1 SQL**, in the app's private storage:

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
  location, removal columns set together, no deleting a location that holds boxes).

### Migrations

Named, ordered, append-only (`Migrations.swift` / `Migrations.kt`): never edit one that has shipped, add a
new one **on both platforms** (and update `fixtures/schema-v1.sql`/`.json`'s successors). iOS tracks them in
GRDB's table, Android in `PRAGMA user_version`. Neither platform ever erases the database on a schema change;
Android refuses a downgrade.

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

## Privacy

No analytics, crash reporting, advertising or network code (lint rules on both platforms; the Android manifest
requests no permission). iOS `PrivacyInfo.xcprivacy` declares no tracking and no collected data. Logs carry
no cabinet contents: iOS uses `os.Logger`'s private default (public interpolation is lint-forbidden), Android
logs only the failure stage and exception class through `AppLog`.

## Localization

French (fallback) and English on both platforms: iOS String Catalogs, Android `values/` (French) and
`values-en/`, with per-app language selection on Android 13+. The same screen says the same thing on both.
