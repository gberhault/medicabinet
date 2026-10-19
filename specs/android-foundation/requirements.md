# Android foundation — Requirements

Status: **Approved 2026-10-19**
Epic: #31 · Children: #32 – #38 · Branch: `android/feature/31-android-foundation`

## Introduction

MediCabinet 0.1.0 exists on iOS only (epic #1). Before any feature epic starts, Android must
reach the same foundation, so that every later epic (reference data #7, bulk scan #12,
inventory #17, expiry #22, export/import #26) ships on both platforms with the same behaviour.
The approach was confirmed on 2026-10-19: a **native Kotlin** app (no Kotlin Multiplatform),
kept behaviourally identical to iOS by **shared language-neutral fixtures** that both test
suites run; **one export format** for both platforms; a **Riding-style monorepo**.

> We are bringing Android to parity with iOS 0.1.0 and reorganizing the repository into
> `ios/`, `android/` and shared `fixtures/`, so that each later epic is specified once and
> built twice against the same test vectors. We are **not** sharing code between Swift and
> Kotlin, and we are **not** adding any feature beyond 0.1.0.

## Glossary

Same as the iOS foundation spec ([`../foundation/requirements.md`](../foundation/requirements.md)),
plus:

- **Shared fixture** — a JSON test-vector file under `fixtures/` that both the Swift and the
  Kotlin test suites load and must pass.
- **Kotlin core** — the `:core` Gradle module: pure Kotlin/JVM, the Android twin of
  `MediCabinetCore`.
- **Product** — iOS app or Android app; each has its own version, changelog and tags.

## User stories and acceptance criteria

### M — Monorepo restructure (#32)

*As a developer, I want each product in its own folder with its own version, so that iOS and
Android evolve and release independently in one repository.*

1. The iOS project (project.yml, Config, app/test sources, `Packages/MediCabinetCore`) SHALL
   live under `ios/`; the Android project SHALL live under `android/`; shared test vectors
   SHALL live under `fixtures/`.
2. WHEN the iOS move is done, the iOS gate SHALL pass with the same tests and no lower
   coverage than before the move.
3. Each product SHALL have its own gate script (`scripts/ios-local-ci.sh`,
   `scripts/android-local-ci.sh`); `scripts/local-ci.sh` SHALL run the shared-fixture checks
   and both product gates.
4. Branch names SHALL use `ios/…` or `android/…` for single-product work, `chore/…` for
   repo-wide work with no product change, and the plain `feature|bugfix|refactor/<issue>-…`
   form for work that ships on **both** platforms.
5. Each product SHALL have its own semver clock and changelog: iOS (`ios/project.yml`
   `MARKETING_VERSION`, `ios/CHANGELOG.md`, tags `ios/vX.Y.Z`), Android
   (`versionName`/`versionCode` in the app Gradle file, `android/CHANGELOG.md`, tags
   `android/vX.Y.Z`). The existing `v0.1.0` tag SHALL stay as history, and `ios/v0.1.0`
   SHALL be added on the same commit.
6. `CLAUDE.md` SHALL describe both platforms: product vision, product decisions
   ("Platforms: iOS and Android, native"), structure, commands, branch and release rules per
   product.

### S — Shared fixtures (#33)

*As a developer, I want one set of test vectors for both platforms, so that the two
implementations cannot silently disagree.*

1. Shared fixtures SHALL be JSON files under `fixtures/`, each carrying a `formatVersion` and a
   short description of its cases.
2. `fixtures/expiry-dates.json` SHALL list valid and invalid (year, month, day-or-null) inputs
   with the expected result: `lastValidDay` and `sortKey`, or the error kind (`invalidYear`,
   `invalidMonth`, `invalidDay`).
3. Both the Swift core and the Kotlin core SHALL pass every case of every shared fixture.
4. WHEN a case is added to a fixture, both test suites SHALL run it without code changes.
5. WHEN a fixture case fails on one platform, that platform's gate SHALL fail.
6. Error kinds and enum raw values that cross platforms (expiry errors, `RemovalReason`,
   `ExpiryStatus`, `CategoryID.uncategorized`) SHALL use the same names on both platforms.

### A — Android project and app shell (#34)

*As an Android user, I want to install the app and find the same structure as on iPhone.*

1. The Android project SHALL build from a clean clone with the committed Gradle wrapper and a
   JDK 17+, with modules `:app` (Compose UI) and `:core` (pure Kotlin).
2. `applicationId` SHALL be `com.medicabinet.app`, minimum Android 8.0 (API 26), target the
   latest stable API installed (36).
3. Release signing material SHALL never be committed; debug builds SHALL need none.
4. WHEN the app launches, the system SHALL show a bottom navigation bar with four
   destinations: Scan, Cabinet, Expiring, Settings — each with an icon and a localized label.
5. WHEN a destination whose feature is not built yet is opened, the system SHALL show the
   same localized empty state as iOS, never a blank screen.
6. WHEN the user opens Settings → About, the system SHALL show the app name, `versionName`
   and `versionCode`, and the no-medical-advice statement.
7. The shell SHALL support font scaling up to 200%, light and dark themes, TalkBack labels on
   every control, and 48 dp minimum touch targets.
8. WHEN the database cannot be opened, backed up or migrated, the system SHALL show the same
   blocking error states as iOS (storage / backup / migration) with **Retry**, and SHALL NOT
   delete or recreate the database.

### K — Kotlin core and domain model v1 (#35)

*As a developer, I want the Android domain rules in a pure Kotlin module, so that they are
tested in seconds without an emulator.*

1. `:core` SHALL build and test with `./gradlew :core:test` on the JVM and SHALL NOT depend on
   the Android SDK, Room or Compose.
2. `:core` SHALL define the same v1 types as `MediCabinetCore`: `ExpiryDate`, its error kinds,
   `Clock` (system and fixed), `Box`, `Location`, `ProductRef`, `CategoryID`, `ExpiryStatus`,
   `RemovalReason`, with UUID identities and `createdAt` / `updatedAt`.
3. `ExpiryDate` SHALL be constructible only through validation; invalid input SHALL be
   rejected with its error kind, never clamped or guessed.
4. A box with no expiry SHALL be representable and SHALL NOT be treated as OK.
5. Domain code SHALL obtain the current time only through the injected `Clock`.
6. `:core` SHALL define the repository ports with the same semantics and error kinds as iOS
   (`notFound`, `unknownLocation`, `duplicate`, `duplicateDefault`), an in-memory fake, and a
   reusable contract test suite.

### P — Persistence, backup and bootstrap (#36)

*As an Android user, I want my cabinet stored safely, so that it survives app updates.*

1. The database SHALL hold the same v1 data as iOS (locations and boxes, same columns and
   meaning) and SHALL enforce the same invariants as iOS v1 (lengths, 14-digit GTIN, month and
   day ranges, expiry parts set together, removal reason and date set together, a single
   default location, no deletion of a location that holds boxes).
2. The schema SHALL evolve only through named, ordered migrations, with the schema history
   committed; destructive fallback (dropping data on a schema change) SHALL never be enabled.
3. WHEN an existing database has pending migrations, the system SHALL copy it to the backups
   folder before migrating, keep the 5 most recent copies, and take no copy for a brand-new
   database.
4. WHEN the copy cannot be written, the system SHALL NOT migrate and SHALL show the backup
   error.
5. WHEN a migration fails, the system SHALL put the pre-migration copy back and show the
   migration error.
6. Database work SHALL run off the main thread; views SHALL reach data only through the
   repository ports, and the storage library SHALL be confined to the persistence package.
7. Removing a box SHALL keep it as a removed record with reason and date so that undo is
   possible.
8. The database and its backups SHALL live in the app's internal storage (never external
   storage).
9. WHEN the cabinet has no location, the system SHALL create one default location with a
   localized name ("Armoire à pharmacie" / "Medicine cabinet").
10. The Android repositories SHALL pass the same contract checks as the iOS ones (same check
    list, adapted to each test framework).

### G — Android gates and local CI (#37)

*As a developer, I want one command that tells me whether an Android branch is PR-ready.*

1. WHEN `bash scripts/android-local-ci.sh` runs, the system SHALL in order: build the debug
   app, run detekt and ktlint (new findings only against committed baselines), run the
   architecture rules, run `:core` and `:app` unit tests (including Robolectric Compose UI
   tests), enforce the coverage gate, check string parity, check the version/CHANGELOG
   lockstep, and compile the instrumented tests (build only).
2. WHEN any step fails, the script SHALL exit non-zero and name the failing step; WHEN the
   JDK or Android SDK is missing, it SHALL say how to install it.
3. The coverage gate SHALL fail WHEN line coverage is below 80% for `:core` or for `:app`.
4. Architecture rules SHALL fail on: Android/Room/Compose imports in `:core`; reading the wall
   clock in `:core` outside the system clock; storage-library imports outside the persistence
   package; any network API or the `INTERNET` permission.
5. Every rule and gate SHALL have a fixture proving it fails when it should.

### I — i18n, privacy and docs (#38)

1. The Android UI SHALL ship in French (default resources) and English, with the same
   wording as iOS for the same screens; WHEN a key lacks a value or its placeholders differ
   between languages, the string check SHALL fail.
2. The app SHALL declare no `INTERNET` permission and contain no analytics, crash-reporting or
   advertising SDK; logs SHALL not contain inventory contents.
3. Android device backup (Auto Backup) SHALL include the database and its backups, so a
   restored phone gets the cabinet back (parity with iCloud backup on iOS).
4. Dates SHALL be formatted with the user's locale.
5. The first Android version SHALL be `0.1.0` / `versionCode 1` with a matching
   `android/CHANGELOG.md` entry.
6. `docs/architecture.md` and `docs/development.md` SHALL cover both platforms; `README.md`
   SHALL explain how to build and install on Android.
7. After merge, `android/v0.1.0` SHALL be tagged and a GitHub release SHALL carry the debug
   APK (as in Riding).

## Out of scope

- Any feature beyond iOS 0.1.0 (scanning, reference data, inventory, expiry rules,
  notifications, export/import) — those epics will now be specified for both platforms.
- Kotlin Multiplatform or any other cross-language code sharing.
- Play Store publishing, release keystore setup, in-app update.
- Android tablets / foldables layout; Wear OS.
- GitHub Actions (local CI only, as on iOS).

## Resolved questions (approved 2026-10-19)

1. **Fallback language** — French in the default `values/` folder, like iOS.
2. **Minimum Android version** — Android 8.0 (API 26), same as Riding.
3. **Distribution** — debug APK attached to the GitHub release for sideloading; Play internal
   testing later.
