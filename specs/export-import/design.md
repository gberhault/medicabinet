# Export and import of the cabinet — Design

Status: **Approved 2026-10-19**
Epic: #26 · Requirements: [`requirements.md`](requirements.md) · Platforms: iOS + Android

The archive format is written **once as shared fixtures** (golden files and validation
cases) and implemented **twice in the cores**: reader, writer, validator, migration and the
transfer use case. The apps add file I/O, the persistence transaction and the screens. This
design assumes the state of `develop` **after the bulk-scan epic (#12) has merged**: schema
v2 (`box.deletedAt`, unique live serial), the scan tab and its view model. Section numbers
are referenced from `tasks.md`.

## 1. Key decisions

### 1.1 A small strict JSON reader and canonical writer in each core

| Option | Pros | Cons | Verdict |
|--------|------|------|---------|
| Platform JSON (`JSONSerialization` / `JSONDecoder` on iOS, `kotlinx-serialization-json` on Android) | No parser code to own | Duplicate keys silently "last wins"; `NSNumber` mixes booleans and numbers; each library treats lone surrogates, big integers and deep nesting differently, so the two platforms could disagree on the same hostile file; builds a full tree (≈ 10× the file size in memory); adds `kotlinx-serialization` to `:core` main | Rejected |
| `Codable` / `@Serializable` models | Little code | Unknown keys ignored by default (V2 requires refusing them); error paths differ per platform; same memory and tree issues | Rejected |
| **Own strict pull reader + canonical writer, in both cores** | Same behaviour on both platforms by construction; duplicate keys, depth, string size and integer range checked while reading; no tree in memory (the mapper reads straight into the model); byte-identical output on both platforms (F8); no dependency | About 300 lines per platform to own; needs fuzzing | **Chosen** |

- The reader implements RFC 8259 strictly: UTF-8 only (overlong forms, surrogate code points
  and invalid bytes are refused), a leading UTF-8 BOM is skipped, nothing may follow the
  root value, strings ≤ 4 096 bytes once decoded, `\u` escapes with surrogate pairs (a lone
  surrogate is refused), and numbers read as integers in the Int64 range (any fraction or
  exponent is a type error where an integer is expected). Duplicate keys in one object are
  refused. There is no depth limit to configure: the reader never descends into a value
  the schema does not expect (an unknown key is refused at once), so the nesting it reads
  is bounded by the schema (four levels).
- The writer emits **canonical** JSON (§2.3). Both cores produce the same bytes, which is
  how X7's cross-platform check stays simple: one golden file, compared byte for byte on
  both platforms.
- Coverage: 100 % of branches, plus the fuzz suite (§11).

### 1.2 The automatic backup is written inside the replace transaction

`CabinetArchiveStore.replace` opens **one write transaction** (GRDB `write`, Android
`beginTransaction`, which is exclusive). Inside it, it:

1. reads the current cabinet;
2. calls the backup hook, which encodes the cabinet and writes the backup file, then reads
   it back and decodes it;
3. deletes every box, then every location (boxes first, because of `ON DELETE RESTRICT`);
4. inserts the archive's rows.

Any throw rolls everything back. No scan can land between the backup and the replace, so
the backup is exactly what the import replaced (I5). The transaction holds the write lock
for a few hundred milliseconds on a 1 000-box cabinet, off the main thread. Pruning old
backups runs **after** the commit, and a pruning failure is logged, not shown (B2).

### 1.3 Automatic backups are archives, next to the pre-migration copies

- iOS: `Application Support/MediCabinet/Backups/`. Android: `files/backups/`. These are the
  folders the pre-migration copies already use, already protected and already included in
  iCloud / Auto Backup and device transfer (B1). No backup-rule change is needed.
- Names: `pre-import-<UTC yyyyMMdd-HHmmss-SSS>.medicabinet` and
  `pre-restore-<…>.medicabinet`. The date and the reason are read from the name, so no
  file-date API is used (and iOS needs no required-reason declaration). The existing
  `BackupService` only touches `pre-migration-*`, so the two never prune each other's files.
- Files are written to `<name>.partial`, decoded back, then renamed. The keep count is
  **3** (`ArchiveBackups.defaultKeepCount`), counted over both prefixes together.

### 1.4 File type and entry points

| | iOS | Android |
|--|-----|---------|
| Type | Exported UTType `com.medicabinet.archive`, conforms to `public.data` (not `public.json`, so other apps don't offer to open it and the Files preview stays opaque); extension `medicabinet`; MIME `application/vnd.medicabinet+json` | MIME `application/vnd.medicabinet+json` for everything the app writes or shares |
| Export | Summary + warning → `UIActivityViewController` (includes **Save to Files**) | Summary + warning → **Save to file** (`ActivityResultContracts.CreateDocument`) or **Share** (`ACTION_SEND` + chooser, through a `FileProvider`) |
| Import in app | `.fileImporter(allowedContentTypes: [.medicabinetArchive, .json])` | `ActivityResultContracts.OpenDocument` with `application/vnd.medicabinet+json`, `application/octet-stream`, `application/json` |
| Open with | `CFBundleDocumentTypes` (role Viewer, rank Owner), `LSSupportsOpeningDocumentsInPlace = NO`; `.onOpenURL` | `MainActivity` intent filters (§7.3); `singleTop` + `onNewIntent` |

- Allowing `.json` / `application/json` in the **in-app picker** covers files whose
  extension was changed in transit. The picker only lists them; validation decides.
  Open-with never claims JSON (O2).
- iOS copies an opened file into `Documents/Inbox`. The import flow deletes that copy when
  it ends, and launch clears `Documents/Inbox` and `tmp/Export`. Android reads an opened or
  picked file **immediately** into memory (capped, §1.5), because the URI grant can end
  with the activity.

### 1.5 Limits (named defaults, `ArchiveLimits`)

| Limit | Value | Why |
|-------|-------|-----|
| `maxFileBytes` | 32 MiB | A real box is ≈ 650 bytes in the file; 1 000 boxes ≈ 0.7 MB. 32 MiB holds the box limit even with maximum-length fields. Checked on the byte count before parsing; reading stops at `max + 1` bytes. |
| `maxBoxes` | 20 000 | 20× the 1 000-box target. A cabinet with all its history (≈ 300 boxes a year) reaches it in decades. |
| `maxLocations` | 200 | Far above any household. |
| `maxStringBytes` | 4 096 | Longer than any field allows (200 code points × 4 bytes). Refuses huge strings before decoding them. |

The encoder checks the same limits. A cabinet over a limit cannot be exported (`tooLarge` /
`tooManyItems`, shown as an error), so the app never writes a file it would refuse. Memory
at the maximum is about the file bytes plus the decoded model (≈ 60 MB on Android), which is
fine for API 26 heaps.

### 1.6 "The cabinet changed" signal

Repositories are pull-based today. After a replace, every screen must show the new cabinet
without a restart (I7). The app environment gains a **cabinet revision**: an
`@Observable @MainActor final class CabinetRevision` on iOS, a `StateFlow<Long>` on Android.
It is bumped after a successful replace. Screens and view models that cache cabinet data
(scan session, later inventory and expiring screens) reload when it changes. The scan
session **ends** on a change, because its review list points at boxes that no longer exist.

### 1.7 Two requirement amendments (made in `requirements.md` together with this design)

- **E6** — Android's share sheet never reports when the receiving app has finished reading the
  file (Gmail reads it at send time), so deleting it "once the share finishes" would break
  the share. Amended: the temporary file is deleted when the platform reports the end (iOS),
  otherwise at the next export or next launch (Android). It lives in `cacheDir/export/`:
  internal, not backed up, reachable only through the non-exported provider.
- **F7** — "never stricter than the database" cannot hold for `box.category`, which has no
  SQL constraint. Amended: validation accepts every value the app can write (the SQL rules,
  and for unconstrained columns a limit above anything the app writes), and the encoder
  validates its own output, so an export that could not be imported back is never produced.

## 2. Archive format v1

### 2.1 Example (canonical form)

```json
{
  "archive": "medicabinet",
  "formatVersion": 1,
  "exportedAt": "2026-10-19T08:15:30.123Z",
  "app": {
    "name": "MediCabinet",
    "platform": "ios",
    "version": "0.3.0",
    "build": "3"
  },
  "locations": [
    {
      "id": "6F1C2A7E-0B1D-4C8E-9A51-0D3B1E2F4A5B",
      "name": "Salle de bain",
      "isDefault": true,
      "sortOrder": 0,
      "createdAt": "2026-10-01T09:00:00.000Z",
      "updatedAt": "2026-10-01T09:00:00.000Z"
    }
  ],
  "boxes": [
    {
      "id": "A3E5C0D2-7F64-4B1A-8E9D-2C5B7A1F3E60",
      "gtin": "03400936404564",
      "productName": "DOLIPRANE 1000 mg, comprimé",
      "category": "pain-fever",
      "categoryOverridden": false,
      "locationId": "6F1C2A7E-0B1D-4C8E-9A51-0D3B1E2F4A5B",
      "expiry": {
        "year": 2027,
        "month": 4,
        "day": null
      },
      "lot": "B1234",
      "serial": "1234567890AB",
      "isOpened": false,
      "removal": {
        "reason": "usedUp",
        "at": "2026-10-10T18:00:00.000Z"
      },
      "createdAt": "2026-10-02T10:00:00.000Z",
      "updatedAt": "2026-10-10T18:00:00.000Z",
      "deletedAt": null
    }
  ]
}
```

### 2.2 Fields and rules (V2)

| Path | Type | Rule |
|------|------|------|
| `archive` | string | exactly `"medicabinet"`. Must be the **first** key, or the file is `notArchive` |
| `formatVersion` | integer | must be the **second** key. `1…current` supported; `> current` → `newerVersion`; `< 1` → `unsupportedVersion` |
| `exportedAt` | date | §2.4 |
| `app.name` | string | 1–50 code points |
| `app.platform` | string | `ios` or `android` |
| `app.version`, `app.build` | string | 1–20 code points (`?` allowed: what `AppInfo` reports when unknown) |
| `locations` | array | 1…`maxLocations` items; exactly one with `isDefault: true` |
| `location.id`, `box.id` | UUID | canonical 8-4-4-4-12 hex, either case, stored upper-case. Unique among locations and, separately, among boxes (the database rule) |
| `location.name` | string | 1–100 code points (SQL `CHECK`) |
| `location.sortOrder` | integer | 32-bit signed range (the Kotlin model is `Int`) |
| `boxes` | array | 0…`maxBoxes` items |
| `box.gtin` | string or null | exactly 14 ASCII digits (SQL `CHECK`; the check digit is **not** verified, because the database does not verify it) |
| `box.productName` | string | 1–200 code points |
| `box.category` | string | 1–100 code points. Unknown ids kept as written (F10) |
| `box.locationId` | UUID | must be a location in the file → else `unknownLocation` |
| `box.expiry` | object or null | `year`, `month`, `day` (integer or null) passed through `ExpiryDate.make`. Any error → `invalidValue` (V3, never "unknown") |
| `box.lot`, `box.serial` | string or null | 1–20 code points |
| `box.removal` | object or null | `reason` ∈ `usedUp`, `thrownAway`; `at`: date |
| `box.deletedAt` | date or null | tombstone |
| (gtin, serial) | — | unique among boxes with a serial and `deletedAt == null` → else `duplicateSerial` |

- **Every key is required**, and absent values are an explicit `null`. Unknown keys and
  duplicate keys → `invalidStructure`. Key order is free, except the first two top-level
  keys (so the version is known before the rest is read).
- **Lengths are counted in Unicode code points**, like SQLite's `length()`. Not Swift
  `Character`s (grapheme clusters), not Kotlin `String.length` (UTF-16 units). Both
  platforms use a shared `codePointCount` helper with fixture cases (combining accents,
  emoji, CJK).
- `expirySortKey` is not stored. It is recomputed on import (F5).

### 2.3 Canonical form (writer)

- Keys in the order of §2.1. Every non-empty object and array is written over several lines
  (one key or item per line, two-space indentation), `": "` after keys, LF line endings,
  and a final newline. Empty arrays are written `[]`.
- Locations sorted by `sortOrder`, then `id`. Boxes sorted by `createdAt`, then `id`. Ids
  upper-case.
- Strings: escape `"` and `\`, `\b \f \n \r \t`, other code points below U+0020 as
  lower-case `\u00xx`. Everything else, including `/` and non-ASCII, is written raw in
  UTF-8.
- Dates: §2.4, always with milliseconds.

### 2.4 Dates

`yyyy-MM-ddTHH:mm:ss.SSSZ`, UTC, exactly this shape (24 characters), parsed strictly
(range-checked fields, no other time zone, no other precision). It matches the database
precision (milliseconds), so round trips are exact (F7).

### 2.5 Versions and migration

`ArchiveFormat.currentVersion = 1`. The decoder reads `formatVersion`, then dispatches to that
version's reader. Each reader produces the current model through
`ArchiveMigrations.migrate(from:)`, which has nothing to do for v1. A later v2 adds a v2
reader and a v1 → v2 step, and v1's reader is never edited (F9). `Archive.sourceFormatVersion`
keeps the file's original version for the preview (I8).

### 2.6 Error kinds (cross-platform, `fixtures/README.md`)

`tooLarge`, `notJSON`, `notArchive`, `unsupportedVersion`, `newerVersion`,
`invalidStructure`, `invalidValue`, `tooManyItems`, `duplicateId`, `unknownLocation`,
`defaultLocation`, `duplicateSerial`. Each `ArchiveError` carries `kind` and a `path` such as
`boxes[12].expiry.month`. The path holds indexes and key names only, never values (V5). It
appears in tests and logs, not in the UI.

### 2.7 Decoding order and error attribution (fixed by `archive-cases.json`)

1. Size (`tooLarge`, path `null`), then the whole byte string must be valid UTF-8 (a BOM is
   skipped; else `notJSON`).
2. The root must be an object whose first member is `archive` = `"medicabinet"` and the
   second `formatVersion` (an integer): anything else is `notArchive`. A root is classed by its
   first byte alone: one that cannot start a JSON value is `notJSON`, any other root that is
   not an object (even `true`, or text starting like it) is `notArchive`. A version
   below 1 is `unsupportedVersion`, above the current one `newerVersion`; both stop the read
   there (the body of a newer file is never read). All three have path `null`.
3. The rest is read member by member, in file order, into the model. The first fault wins:
   syntax faults are `notJSON` (path `null`); a missing, unknown or duplicate key, a wrong
   JSON type or a float where an integer is expected is `invalidStructure`; an unreadable
   value (uuid, date, expiry through `ExpiryDate.make`, removal reason, an integer beyond
   Int64 or a sort order beyond 32 bits, a string over `maxStringBytes`) is `invalidValue`.
   The path is the field, e.g. `boxes[3].expiry.year`. An expiry rejected by
   `ExpiryDate.make` is attributed to `boxes[3].expiry`. A missing key is attributed to
   `<object>.<key>`, reported once the object ends. More than `maxLocations` / `maxBoxes`
   items is `tooManyItems` (path `locations` / `boxes`), reported when the extra item
   starts.
4. After the read, `ArchiveValidator` runs on the model, in this order: field limits (lengths
   in code points, platform, GTIN of 14 ASCII digits) as `invalidValue`; `duplicateId`
   (locations, then boxes; path of the second occurrence); `defaultLocation` (not exactly one
   default, path `locations`, which includes an empty list); `unknownLocation` (path
   `boxes[i].locationId`); `duplicateSerial` (same GTIN and serial among boxes that are not
   deleted; boxes without a GTIN are never compared, like the unique index; path of the second
   box's `serial`). The encoder runs the same validator before writing.
5. `CabinetSummary.unknownExpiry` counts the boxes **in the cabinet** (not removed, not
   deleted) without an expiry. `needsBackup` is false only when the cabinet has no box of any
   kind and at most one location (a database with no location at all has nothing to lose either, and no
   archive could hold it).

## 3. Shared fixtures

```
fixtures/
  archive-cases.json                # decode cases: expected summary, or error kind + path
  archive-code-points.json          # code-point counting cases
  archive/v1/golden.medicabinet     # canonical v1 file, every field variant; kept forever
```

- `golden.medicabinet` holds 3 locations (one default) and boxes covering: day and month
  precision, unknown expiry, the 2000 and 2099 year bounds, removed (both reasons),
  deleted, deleted with a serial that a live box reuses, unknown category, no GTIN, maximum
  lengths (200-code-point name with combining accents and emoji), every optional field null.
- `archive-cases.json` cases:
  `{ "name", "file": "archive/v1/golden.medicabinet" | "content": "<inline JSON text>" | "contentBase64": "<bytes>" | "generate": {"boxes": n, "locations": n, "padNameTo": n}, "expect": { "summary": {…}, "needsBackup": bool } | { "error": { "kind", "path" } } }`
  (`contentBase64` for byte-level cases: invalid UTF-8, a BOM).
  `summary` holds `formatVersion`, `sourceFormatVersion`, `exportedAt`, `app`, `locations`,
  `inCabinet`, `removed`, `deleted` and `unknownExpiry`. Hostile cases are inline: every
  error kind, unknown key, duplicate key, wrong types, `1.0` as an integer, a lone
  surrogate, invalid UTF-8, a BOM (accepted), trailing data, deep nesting under an unknown key, `formatVersion` 0 and
  2, a missing default, two defaults, expiry `2027-02-29`, year 2100, an `"UNKNOWN"` removal
  reason, and so on. Generated cases cover `maxBoxes + 1`, `maxLocations + 1` and
  `maxFileBytes + 1`.
- **Canonical check**: both suites decode `golden.medicabinet`, encode the result with the
  same header, and compare **byte for byte** with the file. That proves the two writers
  agree, and that each platform imports the other's files (X7).
- **Version check**: a core test asserts that `archive/v<n>/golden.medicabinet` exists for
  every `n` in `1…currentVersion` (F9).
- `fixtures/archive-store-contract.json` lists the store checks of §5.1 (its own file, not an addition to
  `repository-contract.json`: the store is a separate port, and the bulk-scan epic edits that list too).
  `fixtures/README.md` lists the files, the error kinds, the `ios` / `android` platform values and the MIME / UTType.
- `scripts/check-fixtures.py` also parses `fixtures/archive/**/*.medicabinet` as JSON with
  `"archive": "medicabinet"`. `scripts/tests/test-shared-fixtures-break.sh` gains a broken
  `archive-cases.json` copy and a one-byte-changed golden file, each of which must fail both
  suites.

## 4. Core (both platforms)

New folder `Sources/MediCabinetCore/Archive/` / package `com.medicabinet.core.archive`, with
the same names on both. Kotlin uses `Instant`, `UUID`, `ByteArray` and `ArchiveException`,
where Swift uses `Date`, `UUID`, `[UInt8]` / `Data` and typed throws.

```swift
public enum ArchiveFormat {
    public static let magic = "medicabinet"
    public static let currentVersion = 1
    public static let fileExtension = "medicabinet"
    public static let mimeType = "application/vnd.medicabinet+json"
}

public struct ArchiveLimits: Sendable {               // §1.5, named defaults
    public var maxFileBytes = 32 * 1024 * 1024, maxBoxes = 20_000, maxLocations = 200
    public var maxStringBytes = 4_096
    public static let `default` = ArchiveLimits()
}

public struct ArchivedBox: Hashable, Sendable { public var box: Box; public var deletedAt: Date? }
public struct CabinetSnapshot: Hashable, Sendable {
    public var locations: [Location]; public var boxes: [ArchivedBox]
    public var summary: CabinetSummary { get }        // inCabinet, removed, deleted, unknownExpiry, locations
    public var needsBackup: Bool { get }              // false when no box and only the default location (B3)
}
public struct ArchiveApp: Hashable, Sendable { public var name, platform, version, build: String }
public struct Archive: Hashable, Sendable {
    public var exportedAt: Date; public var app: ArchiveApp; public var cabinet: CabinetSnapshot
    public var sourceFormatVersion: Int
}

public enum ArchiveErrorKind: String, Sendable { case tooLarge, notJSON, notArchive, … }   // §2.6
public struct ArchiveError: Error, Equatable, Sendable { public let kind: ArchiveErrorKind; public let path: String? }

public enum ArchiveEncoder { public static func encode(_ archive: Archive, limits: ArchiveLimits = .default) throws(ArchiveError) -> [UInt8] }
public enum ArchiveDecoder { public static func decode(_ bytes: [UInt8], limits: ArchiveLimits = .default) throws(ArchiveError) -> Archive }
// internal: JSONReader (pull tokenizer), CanonicalJSONWriter, ArchiveValidator, ArchiveV1Reader,
//           ArchiveMigrations, ArchiveDates, CodePoints
```

- `ArchiveValidator` holds the semantic rules (§2.2). The decoder runs it after reading, and
  the encoder runs it before writing (F7 amended, §1.7).
- `CategoryID` already keeps any raw string, so unknown categories survive (F10). Showing
  them as uncategorized is the category UI's job (#10).

### 4.1 Ports

```swift
/// The whole cabinet, for export and import. Implementations: fake, GRDB, SQL.
public protocol CabinetArchiveStore: Sendable {
    /// Every location and every box (in the cabinet, removed, deleted), in one read transaction.
    func snapshot() async throws -> CabinetSnapshot
    /// In one write transaction: read the current cabinet, call `beforeReplacing` with it
    /// (a throw rolls back and is rethrown), replace all rows with `snapshot`, recompute derived
    /// values. Ids, fields and dates are kept as given.
    func replace(with snapshot: CabinetSnapshot,
                 beforeReplacing: @Sendable (CabinetSnapshot) throws -> Void) async throws
}

public enum BackupReason: String, Sendable { case beforeImport, beforeRestore }
public struct ArchiveBackupEntry: Hashable, Sendable, Identifiable {
    public let id: String; public let takenAt: Date; public let reason: BackupReason
    public let summary: CabinetSummary?          // nil = unreadable (B4)
}
/// Automatic backup files. Implementations: in-memory fake, file system (app).
public protocol ArchiveBackupStore: Sendable {
    /// Writes, reads back and decodes; throws if any step fails. Synchronous: runs inside the replace transaction.
    func write(_ bytes: [UInt8], reason: BackupReason, takenAt: Date) throws -> ArchiveBackupEntry
    func list() async throws -> [ArchiveBackupEntry]      // newest first
    func read(_ id: String) async throws -> [UInt8]
    func prune(keeping count: Int) async throws
}
```

### 4.2 `CabinetTransfer` (use case, stateless, injected clock, app identity and limits)

```swift
public struct CabinetTransfer: Sendable {
    public init(store: any CabinetArchiveStore, backups: any ArchiveBackupStore,
                clock: any Clock, app: ArchiveApp, limits: ArchiveLimits = .default)
    public func currentSummary() async throws -> CabinetSummary
    public func exportArchive() async throws -> [UInt8]                     // snapshot → encode
    public func preview(_ bytes: [UInt8]) async throws -> ImportPreview      // decode + current summary
    public func apply(_ preview: ImportPreview, reason: BackupReason) async throws -> ImportResult
    public func backups() async throws -> [ArchiveBackupEntry]
    public func readBackup(_ id: String) async throws -> [UInt8]
    public static func fileName(for date: Date, in timeZone: TimeZone) -> String  // MediCabinet-YYYY-MM-DD.medicabinet, ASCII digits
}
public struct ImportPreview: Sendable { public let archive: Archive; public let file: CabinetSummary
                                         public let current: CabinetSummary; public let willBackUp: Bool }
public struct ImportResult: Sendable { public let imported: CabinetSummary; public let backup: ArchiveBackupEntry? }
```

`apply` calls `store.replace(with:)` with a hook that, when `current.needsBackup`, encodes the
current cabinet (header: now, this app) and calls `backups.write`. A hook failure is
reported as `TransferError.backupFailed`. After the commit it calls
`backups.prune(keeping: 3)`; a prune failure is logged, never thrown. The preview's
`current` summary is informative only: `apply` backs up the cabinet as it is **at apply
time**.

## 5. Persistence (both platforms)

### 5.1 Store implementations

- iOS `Persistence/GRDBCabinetArchiveStore.swift`: `snapshot` = one `read` (all `location`
  rows, all `box` rows including `deletedAt`). `replace` = one `write`: fetch, hook,
  `DELETE FROM box`, `DELETE FROM location`, insert records (sort key from
  `ExpiryDate.sortKey`). Android `persistence/SqlCabinetArchiveStore.kt`, the same through
  `CabinetDatabase.transaction` with parameterized statements, compiled once per replace.
- The store contract (`ArchiveStoreContract`, names and order in `fixtures/archive-store-contract.json`, run by the
  fake and by each real store): a new store snapshots as empty; replace swaps the whole cabinet keeping ids, fields
  and dates; the snapshot includes removed and deleted boxes; the hook receives exactly the cabinet being replaced;
  a throwing hook leaves the cabinet unchanged; a box in an unknown location, a duplicate id, and two default
  locations are each rejected (`RepositoryError.unknownLocation` / `.duplicate` / `.duplicateDefault`) and change
  nothing; replacing with an empty cabinet empties the store. SQL stores add their own tests for what the fake has
  no equivalent of (the recomputed expiry sort key, rollback of a failing insert midway).
- No schema change. This epic ships no migration (v2 comes from #12).

### 5.2 Backup file stores

- iOS `Persistence/FileArchiveBackupStore.swift`, Android
  `persistence/FileArchiveBackupStore.kt`: §1.3 paths and names, `.partial` then decode then
  rename. iOS writes with `.completeFileProtectionUntilFirstUserAuthentication`, the same
  class as the database. `list` decodes each file to fill its summary (3 files, off the
  main thread). An undecodable file is listed with `summary == nil`.

## 6. iOS app

```
ios/MediCabinet/Sources/Features/Transfer/
  TransferSection.swift        # Settings section: Export, Import, Automatic backups (E1, O3, B4)
  ExportViewModel.swift        # @MainActor @Observable: idle / summarizing / confirm(summary) / writing / sharing(URL) / failed
  ExportSummaryView.swift      # counts + health-data warning; Continue / Cancel (E2, E9)
  ActivityView.swift           # UIActivityViewController wrapper with completion → delete temp file (E5, E6)
  ImportViewModel.swift        # reading / checking / preview / applying / done / failed(kind)
  ImportPreviewView.swift      # I1; destructive "Replace my cabinet" + confirmation dialog (I2)
  ImportResultView.swift       # I7 summary, backup kept
  BackupsView.swift            # list, unreadable rows, restore → ImportViewModel (B4, B5)
  ImportCoordinator.swift      # @MainActor @Observable: pending incoming file, busy gating (O1, O4, O5)
  ArchiveFiles.swift           # temp export dir, capped reading of picked / opened URLs, Inbox cleanup
  UTType+Archive.swift         # .medicabinetArchive
```

- `project.yml` (Info.plist): `UTExportedTypeDeclarations` (`com.medicabinet.archive`,
  `public.data`, extension `medicabinet`, MIME), `CFBundleDocumentTypes` (Viewer, Owner),
  `LSSupportsOpeningDocumentsInPlace = NO`. Type name localized in `InfoPlist.xcstrings`.
- `RootView` gets `.onOpenURL`, which hands the URL to `ImportCoordinator`. If bootstrap
  **failed**, the coordinator shows "cannot import: storage unavailable" and deletes the
  Inbox copy (O5). While **loading**, the file is kept until the app is ready.
  `MainTabView` presents the import flow as a `fullScreenCover` driven by the coordinator.
- Busy gating (O4): forms holding unsaved input (scan completion step, manual entry,
  session row editor) register with `ImportCoordinator.busy`. While busy, an incoming file
  shows a banner ("A cabinet file is waiting. Finish or cancel the current entry.") and
  opens as soon as busy clears. The scan view stops the camera while the cover is shown.
- Export temp file: `tmp/Export/<file name>`, written with `.completeFileProtection` and
  deleted in the activity completion handler (E6). `ArchiveFiles.cleanUp()` runs at launch.
- Reading: `startAccessingSecurityScopedResource` for picker URLs; the size is checked with
  `FileHandle` (`seekToEnd`) before reading, refused above `maxFileBytes`.
- `AppEnvironment` gains `transfer: CabinetTransfer` and `revision: CabinetRevision` (§1.6),
  built in `Bootstrap`. `ArchiveApp` comes from `BundleAppInfo` with platform `ios`.
- Log: `Logger(category: "archive")`, with the stage and `ArchiveErrorKind` only.

## 7. Android app

```
android/app/src/main/kotlin/com/medicabinet/app/features/transfer/
  TransferSection.kt  ExportViewModel.kt  ExportSummarySheet.kt  ImportViewModel.kt
  ImportPreviewScreen.kt  ImportResultScreen.kt  BackupsScreen.kt  ImportCoordinator.kt
  ArchiveFiles.kt     # ContentResolver capped read, SAF write, cache/export, FileProvider URI, cleanup
```

### 7.1 Export

- **Save to file**: `CreateDocument(ArchiveFormat.MIME_TYPE)` launched with
  `CabinetTransfer.fileName(…)`. On a URI, it encodes on the injected IO dispatcher and
  writes with `openOutputStream(uri, "wt")`. On failure it calls
  `DocumentsContract.deleteDocument` (best effort) so no truncated file stays behind, then
  shows the error with **Try again** (E7). On success, a snackbar.
- **Share**: writes `cacheDir/export/<name>`, then `ACTION_SEND` with `EXTRA_STREAM` set to
  the `FileProvider` URI, `ClipData`, `FLAG_GRANT_READ_URI_PERMISSION`, and
  `Intent.createChooser`. `cacheDir/export/` is emptied at the next export and at launch
  (E6 amended).
- Manifest: `<provider android:name="androidx.core.content.FileProvider"
  android:authorities="com.medicabinet.app.export" android:exported="false"
  android:grantUriPermissions="true">` with `res/xml/export_paths.xml` →
  `<cache-path name="export" path="export/" />` only.

### 7.2 Import

- `OpenDocument` (§1.4 MIME list) → `ArchiveFiles.readCapped(uri)`: read `maxFileBytes + 1`
  bytes at most, refusing above that, on the IO dispatcher. Then `transfer.preview`.

### 7.3 Open with (O2, open point 2)

```xml
<activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop">
  <!-- files this app wrote or shared carry its MIME type -->
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="content" android:mimeType="application/vnd.medicabinet+json" />
  </intent-filter>
  <intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="application/vnd.medicabinet+json" />
  </intent-filter>
  <!-- saved files often come back as octet-stream: only when the path ends in .medicabinet -->
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="content" android:host="*" android:mimeType="application/octet-stream"
          android:pathPattern=".*\\.medicabinet" />
    <!-- + the usual ".*\\..*\\.medicabinet" variants for dotted paths -->
  </intent-filter>
</activity>
```

- No `application/json`, no `*/*`. A file that matches none of these (the extension was lost
  in transit) goes through **Import a cabinet** in Settings. The import screen says so in its
  empty state.
- `onCreate` / `onNewIntent` → `ImportCoordinator.receive(intent)`. The coordinator reads
  the bytes at once (the URI grant ends with the activity), then gates on bootstrap and
  `busy` exactly like iOS (O4, O5).
- Task T-verify (tasks.md): on a real Android phone and the emulator, check opening from
  Files (Downloads), from Gmail and from Quick Share / Bluetooth received files, and record
  the results in `reports/export-import/README.md`. The filters change only through a spec
  update.

### 7.4 Wiring

- `AppEnvironment` gains `transfer` and `revision: StateFlow<Long>`; `ArchiveApp` comes from
  `PackageAppInfo` with platform `android`. View models get the injected dispatcher. UI state
  is a `StateFlow`.
- Architecture rule (`android-check-architecture.py` + fixture): `FileProvider`,
  `DocumentsContract` and `ContentResolver.openOutputStream` only under
  `features/transfer/`; `java.io.File` writes only there and under `persistence/`.
- `PrivacyTest`: still no `INTERNET`. The permission set is unchanged by this epic, and the
  provider is not exported.

## 8. Flows

1. **Export (iOS)**: Settings → **Export** → `currentSummary` → summary sheet with the
   health-data warning → **Continue** → `exportArchive` (one read transaction, encode,
   validate) → write `tmp/Export/MediCabinet-2026-10-19.medicabinet` → share sheet →
   completion (any result) → delete the temp file.
2. **Export (Android)**: the same summary → **Save to file** → picker → write to the URI →
   "Saved". Or **Share** → `cache/export` → chooser.
3. **Import from Settings**: **Import** → picker → capped read → `preview` (decode, migrate,
   validate, current summary) → preview screen → **Replace my cabinet** → confirmation
   dialog (Cancel is the default) → `apply`: transaction [read current → backup write +
   verify → delete all → insert all] → commit → prune to 3 → revision bump → result screen.
4. **Open with**: Files / Mail / Gmail → app → coordinator (pending until ready and not busy)
   → flow 3 from the preview. On iOS the Inbox copy is deleted when the flow ends.
5. **Invalid file**: decode throws `ArchiveError(kind, path)` → error screen with the
   message for that kind (newer version → "update MediCabinet"). Log: stage + kind. Nothing
   is written.
6. **Backup fails** (storage full): the hook throws → rollback → "The backup could not be
   saved; your cabinet was not changed" + **Try again**.
7. **Restore**: Settings → **Automatic backups** → row → `readBackup` → flow 3, with
   `reason = beforeRestore` and the preview titled "Restore the backup of <date>".
8. **Nothing to lose** (a new phone with only the default location): the preview says no
   backup is needed, and `apply` skips the hook's write.

## 9. Performance

- Budgets (X2: < 2 s for 1 000 boxes). Asserted as the **best of 5 runs** (a busy machine slows some runs, not all) with a
  generated, deterministic 1 000-box cabinet:
  - core: encode ≤ 500 ms, decode + validate ≤ 500 ms (`swift test`, `:core:test`). The test builds are
    unoptimised: decode measures about 150 ms and encode 50 ms there, so the budget is ≥ 3× the measure
    as CLAUDE.md asks, and a release build is many times faster;
  - app integration: `exportArchive` ≤ 600 ms and `apply` (including the backup) ≤ 600 ms,
    on GRDB in a temporary directory and on Android Robolectric SQLite.
  These are about a third of the 2 s target, leaving room for older phones and UI. The
  measured values go in the PR.
- Maximum file: decoding a generated `maxBoxes` file must succeed within 60 s on the desktop
  test hosts (a guard against quadratic behaviour, which would take minutes; it measures about 5 s in a debug
  build), without a full tree in memory (§1.1).
- No I/O on the main thread: every core and store call runs off the main actor / thread. The
  views show staged progress (reading, checking, saving a backup, importing).
- App size: unchanged (no dependency, no resource).

## 10. Privacy impact

| Item | iOS | Android |
|------|-----|---------|
| Permissions | none added | none added (`INTERNET` still absent) |
| New components | document type + exported UTType | non-exported `FileProvider` (one cache folder); intent filters on our MIME and `*.medicabinet` |
| Privacy manifest | unchanged: no file-timestamp or disk-space API used (dates from file names; write errors handled instead of free-space checks) | — |
| Data leaving the app | only the file the user explicitly saves or shares, after the warning | same |
| Stored | up to 3 backup archives in the existing protected, backed-up folder | same (`files/backups/`, already in Auto Backup rules) |
| Logging | stage + `ArchiveErrorKind` | `AppLog` stage + kind |

Store labels stay "no data collected" (the user sends the file; the app collects nothing).
Device backup quota: Android Auto Backup is capped at 25 MB per app, and 3 backups of a
normal cabinet stay below 3 MB. A pathological cabinet near the limits could exceed the
quota (risk §14).

## 11. Test strategy

| Req | Test (iOS / Android) | Kind |
|-----|----------------------|------|
| F1–F6, F9, F10 | `ArchiveFixtureTests` / `ArchiveFixtureTest` (one per `archive-cases.json` case); golden version-existence test | core, shared |
| F7, F8, X7 | canonical byte-for-byte test on `golden.medicabinet`; property test: random valid cabinets → encode → decode == input | core, shared |
| F7 (DB) | integration: GRDB / SQL cabinet with every field variant → `exportArchive` → `apply` on a fresh database → snapshot equal | integration |
| V1–V3 | fixture cases for every error kind and path; `ArchiveValidatorTests`; `CodePointsFixtureTests` | core, shared |
| V4 | `ArchiveFuzzTests`: seeded random bytes, every truncation of the golden file, single-byte mutations of it, deep nesting. Never a crash or a non-`ArchiveError` | core |
| V5, X1 | log-policy lint (both); test that `ArchiveError.description` holds no value | gate + unit |
| E1–E9 | `ExportViewModelTests` (summary, cancel, failure → retry, temp deletion); UI test: warning shown before the share sheet / chooser; Android Robolectric: CreateDocument result → bytes written, failure → document deleted | unit + UI |
| O1–O5 | coordinator tests (pending while loading, refused when failed, waits while busy); iOS UI test via `-UITestingOpenArchive <fixture>` launch argument; Android Robolectric `ACTION_VIEW` intent; manifest test (filters exactly as §7.3, provider not exported); device check (§7.3) | unit + UI + manual |
| I1–I10 | `CabinetTransferTests` with fakes (preview content, older version, nothing-to-lose, hook failure → unchanged, prune after commit, prune failure ignored); contract checks §5.1; UI test: preview → confirm → result, and Cancel changes nothing; scan session ends on a revision change | core + integration + UI |
| B1–B6 | `FileArchiveBackupStoreTests` (names, partial → rename, unreadable listed, keep 3 over both prefixes, pre-migration files untouched); restore flow UI test | integration + UI |
| X2 | performance tests §9 | perf |
| X3, X4 | UI tests at accessibility text size / font scale 2.0; labels; loading / error states | UI |
| X5 | `ios-check-strings.sh`, Android string checks, `check-string-parity.py` | gate |

## 12. i18n impact

New keys on both platforms (fr = source, en), same wording, plurals where counted:
`transfer.section.{title,footer}`,
`transfer.export.{action,title,summary.boxes,summary.removed,summary.locations,summary.empty,warning,continue,cancel,writing,saved,saveToFile,share,failed,retry}`,
`transfer.import.{action,title,reading,checking,backingUp,applying,preview.fileTitle,preview.exportedBy,preview.formatVersion,preview.currentTitle,preview.unknownExpiry,preview.replaceStatement,preview.backupStatement,preview.noBackupNeeded,replace,confirm.title,confirm.message,confirm.replace,done,done.backupKept,pendingBanner,unavailable,wrongFileHint}`,
`transfer.error.{tooLarge,notArchive,damaged,newerVersion,readFailed,backupFailed,replaceFailed}`
(the many validation kinds map to `damaged`),
`transfer.backups.{title,empty,beforeImport,beforeRestore,unreadable,restore,restoreTitle}`.
iOS `InfoPlist.xcstrings`: the document type name ("Armoire MediCabinet" / "MediCabinet
cabinet"). Dates use `Date.FormatStyle` / `DateTimeFormatter`. The file name is not
localized (ASCII).

## 13. Docs and version

- `docs/export-format.md`: v1 field by field (§2), canonical form, error kinds, limits, and
  how a version is added. `docs/export-import.md`: behaviour, backups, privacy, Android
  open-with limits. `docs/architecture.md`: archive layer, backup folder, cabinet revision.
  `docs/README.md` index. `fixtures/README.md`.
- Version: the next MINOR on both apps after bulk-scan (expected iOS 0.3.0 build 3, Android
  0.3.0 versionCode 3), confirmed from the source of truth at bump time.

## 14. Risks

- **Rebase on bulk-scan.** Everything here assumes #12 merged (schema v2, scan view model,
  `delete` / `undoDelete`). If #12's final schema differs, §2.2 and the golden file change
  before the format is frozen.
- **Android open-with** matching depends on the sending app's MIME type and URI. Mitigated:
  in-app import always works, and the device check (§7.3) records the reality.
- **Auto Backup quota** (25 MB) could be exceeded by a pathological cabinet near the limits
  with 3 backups. Accepted, because real cabinets are 100× smaller; noted in the docs.
- **Own JSON reader** is security-sensitive code. Mitigated by strict limits, 100 % branch
  coverage, the shared hostile cases and fuzzing on both platforms.
- **iOS Inbox copies** of opened files hold health data until the flow ends. They are
  deleted at the end of the flow and at every launch.
