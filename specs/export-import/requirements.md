# Export and import of the cabinet — Requirements

Status: **Approved 2026-10-19** (F7 and E6 amended with the design, approved 2026-10-19)
Epic: #26 · Children: #27, #28, #29 · Branch: `feature/26-export-import` · Platforms: iOS + Android

## Introduction

The cabinet exists only on one phone. Without a way to take it out, a lost, broken or replaced
phone means taking stock of the whole drawer again. Moving from an iPhone to an Android phone
(or back) means the same thing. This epic adds one versioned file, the **archive**, that holds
the whole cabinet. Both apps can write it and read it back, today and in every later version.
Import **replaces** the cabinet. It only does so after the whole file has been checked, after
a preview the user has confirmed, and after the current cabinet has been saved as a local
backup that can be restored. A file that is damaged, hostile, or written by a newer app is
refused, and nothing on the phone changes.

> We are adding a versioned `.medicabinet` archive (format v1) to both apps. It is exported
> on explicit user action with a health-data warning. It is imported as a replace, with full
> validation, a preview, a confirmation and an automatic backup, and restorable from
> Settings. The goal is that a household can back up its cabinet, or move it to any new phone
> (iPhone or Android), without losing anything. We are **not** building merge import,
> encryption, cloud or self-hosted sync, or the export of app preferences.

### Decisions taken in the challenge (2026-10-19)

1. **Built on schema v3.** The archive is specified against schema v3 (bulk-scan #40: `box.deletedAt`,
   unique serial; cabinet-inventory: `location.deletedAt`), so archive v1 carries box **and location**
   tombstones and can double as
   the future sync payload. Implementation starts after #12 is merged into `develop` and this
   branch is rebased onto it. Until then, only the spec is written.
2. **The archive holds every box:** boxes in the cabinet, removed boxes (used up / thrown
   away, with reason and date) and deleted boxes (tombstones), plus every location, deleted ones included. A restore
   or a move loses no history.
3. **Android entry points:** export offers **Save to file** (system document picker) and
   **Share** (share sheet). Import goes through the in-app document picker. The app also
   accepts archives opened or shared with it, through narrowly scoped filters that never
   claim every JSON file. **iOS:** share sheet (incl. Save to Files), document picker, and
   opening `.medicabinet` files from Files / Mail.
4. **Automatic backups:** each import (and each restore) first saves the current cabinet
   as an archive in the app's private storage. The newest **3** are kept, and Settings lists
   them and can restore one.
5. **Import is replace only**, with no password and no encryption (Product decisions,
   CLAUDE.md).

## Glossary

- **Archive** — one `.medicabinet` file holding a whole cabinet, in the format of this spec.
- **`formatVersion`** — the integer version of the archive format. It changes whenever the
  shape or meaning of the file changes. Every version ever shipped stays importable.
- **Cabinet data** — every location and every box, including removed and deleted boxes and deleted
  locations.
- **Removed box** — used up or thrown away (`removalReason`, `removedAt`): a stock movement,
  still part of the history.
- **Deleted location** — a location the user deleted, kept only as a tombstone (`deletedAt`) because removed
  and deleted boxes still point at it; invisible in the app (cabinet-inventory spec).
- **Deleted box** — added by mistake, kept only as a tombstone (`deletedAt`) and invisible in
  the app (bulk-scan spec, V).
- **Replace** — the import operation: the cabinet data on the phone becomes exactly the
  cabinet data in the file.
- **Automatic backup** — an archive of the current cabinet that the app writes to its private
  storage just before a replace (import or restore).
- **Shared fixture** — a language-neutral test vector under `fixtures/`, run by both
  platforms.

## User stories and acceptance criteria

Requirement IDs: **F** archive format, **V** validation, **E** export, **O** opening a file,
**I** import, **B** automatic backups and restore, **X** cross-cutting.

### F — Archive format v1 (#27)

*As a household member, I want one file that holds my whole cabinet and that any MediCabinet,
on any phone and in any later version, can read back, so that a backup never goes stale.*

1. The archive encoder, decoder, validator and version migration SHALL live in both cores
   (`MediCabinetCore`, Kotlin `:core`), with no platform, UI or storage framework, and SHALL
   pass every case of the shared archive fixtures on both platforms.
2. An archive SHALL be a single UTF-8 JSON document with the extension `.medicabinet`,
   identifying itself as a MediCabinet archive and carrying its `formatVersion` (`1` for this
   spec).
3. An archive SHALL contain: the export date and time (UTC); the exporting app's name,
   platform (`ios` / `android`), version and build number; every location
   (deleted ones too); every box (in the cabinet, removed, deleted).
4. Each location and box SHALL keep its stable id (UUID), its `createdAt` and `updatedAt`,
   and every persisted field. The expiry SHALL be stored as year, month and optional day, so
   month precision (GS1 `DD = 00`) is never turned into a day. An unknown expiry SHALL be
   stored as absent, never as a placeholder date.
5. Values derived from other fields (e.g. the expiry sort key) SHALL NOT be stored. They SHALL
   be recomputed on import.
6. An archive SHALL NOT contain app preferences (session location, sound, the "expiring soon"
   window), backups, logs, reference data (BDPM), or anything about the device or the user
   beyond F3.
7. Every cabinet the app can hold SHALL export to an archive that the same app version, and
   the app on the other platform, import back into an equal cabinet: same ids, fields and
   dates, at millisecond precision. Validation (V) SHALL accept every value the app can
   store: the database rules, and for columns without a database rule a limit above anything
   the app writes. The encoder SHALL validate its own output, so an export that could not be
   imported back is never produced. *(Amended with the design, §1.7.)*
8. For a given cabinet, the encoder SHALL produce the same bytes every time: stable key order
   and stable entity order. Files from either platform SHALL decode to equal cabinets on both.
9. Every `formatVersion` ever shipped SHALL keep a golden archive file under `fixtures/`
   forever. Every later app version, on both platforms, SHALL import it, migrating older
   versions to the current one in the core. A shipped version's definition SHALL never be
   edited. A change adds a new version and a migration (and is documented in
   `docs/export-format.md`).
10. Category ids SHALL be preserved as written, including ids this app version does not know
    (for instance from a later taxonomy). Such a box SHALL be shown as uncategorized, but its
    id SHALL survive an import followed by an export.

### V — Validation of an untrusted file (#27, #29)

*As a household member, I want a wrong, damaged or hostile file to be refused before
anything happens, so that importing can never break or empty my cabinet.*

1. The whole file SHALL be read and validated before any preview or change. A file that fails
   any rule SHALL be refused as a whole: no partial import, no "skip the bad rows".
2. The system SHALL refuse, with a typed error kind identical on both platforms and listed in
   `fixtures/README.md`:
   - a file larger than the maximum archive size, or holding more locations or boxes than the
     maximum counts. These are named defaults set in the design, well above any real cabinet,
     and are checked before the file is fully loaded where possible;
   - a file that is not UTF-8 JSON, is not a MediCabinet archive, or misses a required field;
   - a `formatVersion` newer than this app supports (the message SHALL ask to update the app)
     or one that never existed;
   - a field with the wrong type, an unknown field, or a value outside the database's rules
     (text lengths, 14-digit GTIN, expiry parts that `ExpiryDate` rejects, removal reason and
     date not set together, unknown removal reason, malformed UUID or date);
   - duplicate ids; a box referring to a location that is not in the file, or a box in the cabinet
     (neither removed nor deleted) referring to a deleted location; not exactly one default
     location, or a default location that is deleted; two boxes that are not deleted sharing the
     same GTIN and serial.
3. An expiry that fails validation SHALL make the file invalid. It SHALL never be clamped,
   corrected, or turned into "unknown".
4. Validation and decoding SHALL never crash or throw an unexpected exception, whatever the
   input. Both platforms SHALL include a fuzz-style test with truncated, mutated and random
   files, and the hostile cases in the shared fixtures.
5. No content of a refused file (names, lots, serials, values) SHALL be logged or shown
   beyond the error kind.

### E — Export (#28)

*As a household member, I want to save or send my whole cabinet as one file whenever I
choose, so that I have a backup or can move to a new phone.*

1. Settings SHALL have a backup-and-transfer section with **Export the cabinet**.
2. Before any file leaves the app, the system SHALL show what will be exported (number of
   boxes in the cabinet, removed boxes, locations) and warn that the file contains health
   data, is not encrypted, and can be read by anyone who gets it. **Cancel** SHALL export
   nothing.
3. The export SHALL be one consistent snapshot of the cabinet data (one read transaction),
   built off the main thread, with a progress / loading state.
4. The file SHALL be named `MediCabinet-YYYY-MM-DD.medicabinet` (the local date of the
   export).
5. iOS: the system share sheet SHALL offer the file, including **Save to Files**. Android: the
   user SHALL choose **Save to file** (system document picker) or **Share** (share sheet).
   Android SHALL grant read access only to the chosen recipient, through the app's own
   provider.
6. Any temporary export file SHALL live in the app's protected private storage, outside
   device backups. It SHALL be deleted when the platform reports that the share finished or
   was cancelled (iOS), otherwise at the next export or the next launch (Android: the share
   sheet does not report when the receiving app has read the file). *(Amended with the
   design, §1.7.)*
7. WHEN writing the file fails (e.g. storage full, the picker's destination refuses it), the
   system SHALL say so and offer **Try again**. The cabinet SHALL be unchanged.
8. WHEN saving to a file succeeds, the system SHALL confirm it. The app SHALL never send,
   upload or keep a copy of an export by itself.
9. An empty cabinet (no box) MAY be exported. The summary (E2) SHALL show it has no box.

### O — Opening an archive with the app (#28, #29)

*As a household member, I want to tap a `.medicabinet` file I received or saved and land in
MediCabinet's import, so that moving to a new phone takes a few taps.*

1. iOS: the app SHALL declare the `.medicabinet` document type. Opening such a file from Files,
   Mail or another app SHALL open MediCabinet on the import flow (I) for that file.
2. Android: the app SHALL accept archives opened or shared with it, and open the import flow
   for them. The filters SHALL be scoped so that MediCabinet is not offered for ordinary JSON
   or arbitrary files. The exact filters SHALL be chosen in the design and verified on a real
   device.
3. Both platforms SHALL offer **Import a cabinet** in the backup-and-transfer section, which
   opens the system document picker.
4. WHEN a file is opened while another flow is in progress (scan session, a form, another
   import), the system SHALL let that flow end safely first, or ask. It SHALL never drop
   unsaved user input silently. The camera SHALL stop while the import flow is shown.
5. WHEN the app cannot open its cabinet (launch error such as "Storage unavailable"), import
   SHALL NOT be offered, and an opened file SHALL be refused with that reason.

### I — Import as replace (#29)

*As a household member, I want to see exactly what an import will do and confirm it before
my cabinet is replaced, so that I never lose my cabinet by mistake.*

1. After a file passes validation (V), the system SHALL show a preview with:
   - from the file: export date, exporting app, platform and version, `formatVersion`, number
     of boxes in the cabinet, removed boxes and locations, and the number of boxes with an
     unknown expiry;
   - from the phone: number of boxes in the cabinet and locations now;
   - a plain statement that the current cabinet will be **replaced**, not merged, and that a
     backup of it will be kept on this phone (or that there is nothing to back up, B3).
2. The import SHALL proceed only after an explicit confirmation on a destructive-styled
   control. Cancel SHALL be the default and SHALL change nothing.
3. On confirmation, the system SHALL take the automatic backup (B1). WHEN the backup fails,
   the import SHALL stop, the cabinet SHALL be unchanged, and the system SHALL say so.
4. The system SHALL then replace all cabinet data with the file's data in **one
   transaction**. Ids, fields and dates SHALL be kept as in the file, not restamped. Derived
   values SHALL be recomputed.
5. No other write (e.g. a scan) SHALL land between the backup and the replace. The backup
   SHALL hold exactly the cabinet that the import replaced.
6. WHEN the replace fails, the transaction SHALL roll back, the cabinet SHALL be exactly as
   before, the backup SHALL be kept, and the system SHALL show the error with **Try again**.
7. WHEN the replace succeeds, the system SHALL confirm with a summary, and every screen SHALL
   show the imported cabinet without a restart.
8. A file of an older `formatVersion` SHALL be migrated in the core before the preview. The
   preview SHALL show the file's original version.
9. The previous cabinet's tombstones and history SHALL NOT be mixed into the result: after a
   replace, the cabinet data equals the file's (F7).
10. Preferences that point at cabinet data (e.g. the last scan location) SHALL fall back to
    their existing default when what they point at no longer exists. They SHALL never crash
    or point at a missing location.

### B — Automatic backups and restore (#29)

*As a household member, I want the cabinet I had before an import to be kept and restorable,
so that importing the wrong file is never final.*

1. Before every import or restore, the system SHALL save the current cabinet data as an
   archive (format F) in the app's private storage. The storage SHALL be protected like the
   database (iOS Data Protection, Android internal storage) and included in device backups
   like the database. A backup SHALL never be shared or sent by itself.
2. The system SHALL keep the newest **3** automatic backups (named default). It SHALL delete
   the oldest only after a new backup has been written and verified to read back.
3. WHEN the current cabinet holds no box and no location other than the default one, there is
   nothing to lose: no backup SHALL be taken, and the preview SHALL say so.
4. The backup-and-transfer section SHALL list the automatic backups, newest first, with the
   date taken, why (before an import / before a restore) and the number of boxes. A backup
   that cannot be read SHALL be listed as unreadable, not hidden.
5. WHEN the user chooses a backup to restore, the system SHALL run the same flow as an import
   (validation, preview, confirmation, backup of the current cabinet, one-transaction
   replace).
6. The pre-migration database backups from the foundation are unchanged by this epic.

### X — Cross-cutting

1. **Privacy**: an export happens only on explicit user action, after the health-data warning
   (E2). No network access, permission, analytics or upload is added. File contents (names,
   lots, serials) SHALL never be logged; logs carry operation stage and error kind only. iOS
   `PrivacyInfo.xcprivacy` and the Android manifest SHALL stay accurate (the design states any
   change).
2. **Performance**: exporting and importing a 1 000-box cabinet SHALL each take **< 2 s** on
   the reference devices, off the main thread, with progress. Validation of the maximum-size
   file SHALL stay within memory limits on an old phone. Timing assertions SHALL use a
   1 000-box fixture cabinet.
3. **Accessibility**: every control labelled for VoiceOver / TalkBack; progress and results
   announced; 44 pt / 48 dp targets; Dynamic Type and 200 % font scale; the warning and the
   "replace" statement do not rely on colour alone; WCAG AA contrast.
4. **Loading, empty and error states** for export, file picking, validation, import, backup
   listing and restore.
5. **i18n**: every new string in French and English on both platforms, with the same wording.
   Dates and counts are formatted with the user's locale, with plural variants.
6. **Docs**: `docs/export-format.md` (every `formatVersion`, field by field, validation
   rules, error kinds), `docs/export-import.md` (behaviour, backups, privacy),
   `docs/architecture.md` (archive layer, backups), `fixtures/README.md` (new fixtures and
   names).
7. **Tests**: success, failure and edge-case tests on both platforms. Golden-file and hostile
   fixtures run by both suites. A cross-platform check that a file written by each platform's
   encoder decodes to the same cabinet on the other. Integration tests of export → import
   round trip and of rollback on failure. UI tests of the export warning, preview /
   confirmation and restore. Core coverage targets 100 % of branches; new app code 90 %.
8. **Version**: a MINOR bump on both apps (the next minor after the bulk-scan release), with
   changelog entries. Exports record that version (F3).

## Out of scope

- Merge import, or importing part of a file (Product decisions: replace only).
- Password protection or encryption of archives (possible later opt-in).
- iCloud, Google Drive or self-hosted sync, and automatic or scheduled exports. The format is
  sync-ready (ids, `updatedAt`, tombstones), but no sync is built.
- Exporting app preferences or settings.
- Deleting or sharing an automatic backup from the app. Backups go away with the app's data.
- Using import as a recovery path when the database itself cannot be opened (possible
  follow-up).
- Human-readable exports (CSV, PDF, printing).

## Open points to confirm during design

1. **Schema at freeze time.** Archive v1 is frozen against the schema on `develop` when this
   epic's implementation starts. *Resolved 2026-10-20: schema v3 (location tombstones) landed
   first and is part of v1. Any further schema change before the format ships is handled the
   same way: the spec is updated first.*
2. **Android open-with filters (O2).** Custom extensions are matched unreliably on Android. The
   design picks the MIME type / path filters, verified on a real device, and states what
   happens with a file whose name lost its extension (e.g. some mail apps).
3. **Limits (V2).** The maximum file size and counts get named defaults with their reasoning
   in the design.
