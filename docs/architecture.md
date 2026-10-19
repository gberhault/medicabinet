# Architecture

MediCabinet is a native iPhone app (iOS 17+, SwiftUI, Swift 6 with strict concurrency). The
cabinet lives only on the device: no server, no analytics, no network request.

## Layers

```
View  →  ViewModel (@Observable, @MainActor)  →  repository protocol (core)  →  GRDB implementation
```

| Where | What |
|-------|------|
| `Packages/MediCabinetCore` | Pure Swift, tested with `swift test` on a Mac. Domain types (`Box`, `Location`, `ProductRef`, `CategoryID`, `ExpiryDate`, `ExpiryStatus`, `RemovalReason`), the injected `Clock`, and the repository ports (`LocationRepository`, `BoxRepository`). It may not import UIKit, SwiftUI, VisionKit or GRDB (lint-enforced) and never calls `Date()` itself. |
| `Packages/MediCabinetCore` → `MediCabinetCoreTestSupport` | `MutableClock`, an in-memory fake store and `RepositoryContract`, the list of checks every repository implementation must pass. |
| `MediCabinet/Sources/Persistence` | The only place that imports GRDB: `AppDatabase`, `Migrations`, records, `GRDBLocationRepository` / `GRDBBoxRepository`, `BackupService`, `Bootstrap`. |
| `MediCabinet/Sources/App` | App entry, `AppEnvironment` (the repositories and clock handed to the UI), `BootstrapModel`, `RootView`, the launch error screen, `Log`. |
| `MediCabinet/Sources/Features` | One folder per feature (today: `MainTabView`, placeholders, `Settings`). |

Dependency injection is manual: `Bootstrap` builds an `AppEnvironment` once and `RootView`
puts it in the SwiftUI environment. Tests build their own with in-memory databases and a fixed
clock.

## The database

- SQLite through GRDB, in `Application Support/MediCabinet/cabinet.sqlite` (WAL mode).
- The directory and database files use iOS Data Protection
  `completeUntilFirstUserAuthentication`: encrypted at rest until the first unlock after boot,
  so notification digests can still be rescheduled while the phone is locked.
- `Application Support` is part of the user's iCloud / iPhone backup (not excluded), so a
  restored phone gets the cabinet back.
- Ids are UUIDs stored as text; every row has `createdAt` / `updatedAt` (repositories stamp
  `updatedAt` from the injected clock). The model is sync-friendly although no sync exists.
- Boxes are never deleted when removed: `removalReason` + `removedAt` mark them as used up or
  thrown away, which is what makes Undo possible.
- Expiry is stored as year/month/day parts plus `expirySortKey` (`YYYY-MM-DD` of the last
  valid day). A box with no expiry has all of them `NULL` and is *unknown*, never OK.
  Month-precision dates (GS1 day `00`) leave `expiryDay` `NULL`.
- Constraints in SQL back up the Swift rules (name lengths, 14-digit GTIN, month range, one
  default location, removal columns set together, restricted location deletion).

### Migrations

`Migrations.migrator` registers named, ordered migrations (`v1`, …). Rules:

1. Never edit a migration that has shipped; add a new one.
2. Never erase the database on a schema change.
3. Each migration runs in its own transaction.

### Backups and failure handling

Before applying pending migrations to an **existing** database, `BackupService` writes a
self-contained copy to `Application Support/MediCabinet/Backups/pre-migration-<UTC time>-<last
applied migration>.sqlite` using SQLite's online backup API, and keeps the newest five.

`Bootstrap` runs on launch, off the main thread:

1. Open the database (create the protected directory on first launch).
2. If migrations are pending on an existing database: back it up. If that fails → stop, show
   "Backup failed" with **Try again**. Nothing is migrated.
3. Migrate. If a migration fails → close the database, put the pre-migration copy back (drop
   `-wal` / `-shm`), show "Update failed" with **Try again**. The copy stays in `Backups/`.
4. Ensure exactly one default location exists (localized name).
5. Prune old backups, hand the `AppEnvironment` to the UI.

On a first launch there is nothing to lose, so no backup is made; a failed first migration
leaves an empty database that **Try again** migrates.

## Privacy

No analytics, crash reporting, advertising or network code (a lint rule forbids
`URLSession` / `URLRequest` / `Network`). `PrivacyInfo.xcprivacy` declares no tracking and no
collected data. Logs use `os.Logger` with the default private level; a lint rule forbids
opting values into public output. Item-related values (lot, serial, names) never go in log
messages.

## Localization

French (development language) and English, in String Catalogs. `scripts/check-strings.sh`
fails when a key lacks a translation in either language.
