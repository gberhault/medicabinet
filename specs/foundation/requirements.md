# Foundation — Requirements

Status: **Draft — awaiting approval**
Epic: #1 · Children: #2, #3, #4, #5, #6 · Branch: `feature/1-foundation`

## Introduction

MediCabinet has no code yet. Every feature epic (reference data #7, bulk scan #12,
inventory #17, expiry #22, export/import #26) needs the same baseline: an iOS project that
anyone can regenerate and build, a pure Swift package for the domain rules, a local database
whose schema can evolve without ever losing the user's cabinet, and quality gates that run
before every PR. This epic delivers that baseline with an app shell a user can install, but
**no end-user feature yet**.

> We are building the project skeleton, the v1 domain model and its storage, and the local
> quality gates, so that every later epic only adds features — and we are **not** building
> scanning, reference data, inventory screens, notifications, or export/import.

## Glossary

- **Box** — one physical medicine box (or non-drug item) in the cabinet. Quantities are
  counted in boxes only (product decision).
- **Location** — a place where boxes are stored (bathroom, kitchen, car, travel kit…).
- **Product reference** — what a box is (GTIN/CIP13, name, category); later filled from BDPM.
- **Expiry date** — the date printed on the box; may be day-precise or month-precise
  (GS1 `DD=00` means "end of month"), or unknown.
- **Core** — the `MediCabinetCore` Swift package (pure Swift, no UIKit/SwiftUI/GRDB).

## User stories and acceptance criteria

### R1 — Reproducible project (#2)

*As a developer, I want the Xcode project generated from a committed spec, so that the
project never drifts and merge conflicts in `.pbxproj` never happen.*

1. WHEN a developer runs `xcodegen generate` on a fresh clone, the system SHALL produce a
   buildable `MediCabinet.xcodeproj` with the app, unit-test and UI-test targets and the local
   `MediCabinetCore` package.
2. The project SHALL target iPhone, iOS 17.0 minimum, Swift 6 language mode with complete
   strict concurrency checking.
3. The signing team SHALL come only from `Config/Signing.local.xcconfig` (gitignored); WHEN
   that file is absent, the system SHALL still build for the simulator.
4. The generated `.xcodeproj` SHALL NOT be committed.

### R2 — App shell (#2)

*As a household member, I want to install the app and see where each feature will live, so
that the app can be tried on a device from day one.*

1. WHEN the app launches, the system SHALL show a tab bar with four tabs: Scan, Cabinet,
   Expiring, Settings — each with an SF Symbol and a localized title.
2. WHEN a tab whose feature is not built yet is opened, the system SHALL show a localized
   empty state stating the feature is coming, never a blank screen.
3. WHEN the user opens Settings → About, the system SHALL show the app name, the version
   (`MARKETING_VERSION`) and build number (`CURRENT_PROJECT_VERSION`), and the statement that
   MediCabinet is an inventory tool and gives no medical advice.
4. The shell SHALL support Dynamic Type up to accessibility sizes, light and dark mode, and
   VoiceOver labels on every control.
5. The app SHALL make no network request.

### R3 — Core package and domain model v1 (#3)

*As a developer, I want the domain rules in a pure Swift package, so that they are tested in
seconds on a Mac without a simulator.*

1. `MediCabinetCore` SHALL build and run its tests with `swift test` on macOS, and SHALL NOT
   import UIKit, SwiftUI, VisionKit or GRDB.
2. The core SHALL define the v1 domain types: `Box`, `Location`, `ProductRef`, `CategoryID`,
   `ExpiryDate`, `ExpiryStatus`, `RemovalReason`, and a `Clock` abstraction.
3. Every `Box` and `Location` SHALL have a stable UUID identity and `createdAt` / `updatedAt`
   timestamps (sync-friendly model, product decision).
4. `ExpiryDate` SHALL represent either a day-precise date or a month-precise date (year +
   month); WHEN month-precise, its last valid day SHALL be the last day of that month,
   including February in leap years.
5. WHEN an `ExpiryDate` is built from an invalid year/month/day combination, the system SHALL
   reject it with a typed error — it SHALL NOT clamp or guess.
6. A box with no expiry date SHALL be representable and SHALL NOT be treated as valid/OK by
   any API (`ExpiryStatus.unknown` exists).
7. Domain code SHALL obtain the current time only through the injected `Clock`.

### R4 — Persistence with versioned migrations (#4)

*As a household member, I want my cabinet stored safely on the phone, so that it survives app
updates.*

1. WHEN the app starts, the system SHALL open (or create) the cabinet database and apply all
   pending migrations before any screen reads data.
2. The schema SHALL evolve only through named, ordered migrations; migration `v1` SHALL create
   the `location` and `box` tables with indexes on the lookup columns (GTIN, location, expiry,
   active/removed state).
3. WHEN a migration fails, the system SHALL leave the database as it was before the attempt
   and show a localized blocking error with the option to retry — it SHALL NOT delete or
   recreate the database.
4. The database file SHALL be protected with iOS Data Protection.
5. Views SHALL access data only through repository protocols; GRDB SHALL be confined to the
   app's `Persistence` layer.
6. All database work SHALL run off the main actor.
7. WHEN a box is removed (used up / thrown away), the system SHALL keep it as a removed record
   with reason and date (soft removal) so that undo is possible.

### R5 — Backup before migrating (#4)

*As a household member, I want a copy of my cabinet taken before the app changes its storage,
so that an update can never lose my data.*

1. WHEN an existing database has pending migrations, the system SHALL copy it to the backups
   folder before applying them.
2. WHEN the backup cannot be written, the system SHALL NOT migrate and SHALL show a localized
   blocking error.
3. The system SHALL keep the 5 most recent pre-migration backups and delete older ones.
4. WHEN the database is new (first launch), the system SHALL NOT create a backup.

### R6 — Default location (#4)

*As a household member, I want a location to exist from the start, so that the first scanned
box always has a place.*

1. WHEN the cabinet has no location, the system SHALL create one default location with a
   localized name (fr: "Armoire à pharmacie", en: "Medicine cabinet").
2. Exactly one location SHALL be the default at any time.

### R7 — Local CI and quality gates (#5)

*As a developer, I want one command that tells me whether a branch is PR-ready.*

1. WHEN `bash scripts/local-ci.sh` runs, the system SHALL in order: generate the project,
   run SwiftLint (strict, against the committed baseline), run the core tests with coverage,
   run the app unit and UI tests on an available iPhone simulator with coverage, enforce the
   coverage gate, check localization completeness, and check the version/CHANGELOG lockstep.
2. WHEN any step fails, the script SHALL exit non-zero and name the failing step.
3. The coverage gate SHALL fail WHEN line coverage is below 80% for the core package or for
   the app target.
4. The version check SHALL fail WHEN the top `CHANGELOG.md` entry differs from
   `MARKETING_VERSION` in `project.yml`.
5. WHEN `swiftlint` or `xcodegen` is not installed, the script SHALL fail with the install
   command.

### R8 — Localization (#6)

*As a French- or English-speaking user, I want the whole app in my language.*

1. The app SHALL use French as development language and ship English; all user-facing text
   (including permission and Info.plist strings) SHALL live in String Catalogs.
2. WHEN a String Catalog key lacks a translated value in any shipped language, the
   localization check SHALL fail.
3. Dates SHALL be formatted with the user's locale.

### R9 — Privacy baseline (#6)

1. The app SHALL ship a `PrivacyInfo.xcprivacy` declaring no tracking, no collected data, and
   every required-reason API it uses.
2. The app SHALL contain no analytics, crash-reporting, or advertising SDK.
3. Logs SHALL not contain inventory contents (item-related values logged as `.private`).

### R10 — Versioning and docs (#6)

1. The first version SHALL be `0.1.0` build `1`, with a matching `CHANGELOG.md` entry.
2. `docs/README.md` SHALL index the docs; `docs/architecture.md` SHALL describe the layers,
   the database location, migrations and backups; `docs/development.md` SHALL list setup and
   build/test commands.
3. `README.md` SHALL explain how to build and install on a device.

## Out of scope

- Barcode scanning, the GS1 parser, camera permission (#12).
- BDPM reference data and category taxonomy (#7) — only the `CategoryID` type and an
  "uncategorized" value exist.
- Inventory, location management and box detail screens (#17).
- Expiry status *rules* such as the "expiring soon" window, and notifications (#22) — only the
  `ExpiryDate` / `ExpiryStatus` types exist.
- Export/import (#26).
- iCloud sync, App Store submission, iPad layout.
- GitHub Actions (local CI only).

## Open questions (to answer before approval)

1. **Bundle identifier** — proposed `com.medicabinet.app`. OK?
2. **Display name** under the icon — proposed "MediCabinet". OK?
