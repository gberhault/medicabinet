# Export, import and automatic backups

One file, `MediCabinet-YYYY-MM-DD.medicabinet`, holds the whole cabinet. It is the backup, and the way to move
the cabinet to another phone, iPhone or Android, in either direction. Spec:
[`specs/export-import/`](../specs/export-import/) (epic #26). The file format is in
[export-format.md](export-format.md). Both apps behave the same and say the same thing
([`strings.md`](../specs/export-import/strings.md) is the wording table).

## Export

Settings → **Backup and transfer** → **Export the cabinet**.

1. The app shows what will be exported (boxes in the cabinet, removed boxes, locations) and warns that the file
   contains **health data**, is **not encrypted** and can be read by anyone who gets it. **Cancel** exports nothing.
2. iOS: the system share sheet (it includes **Save to Files**). Android: **Save to file** (system document
   picker) or **Share** (share sheet).
3. The summary is one consistent snapshot, built off the main thread. An empty cabinet can be exported.
4. A failure (storage full, a destination that refuses the file) is reported with **Try again**; the cabinet is
   unchanged. The app never sends, uploads or keeps a copy of an export by itself.

The temporary file lives in private storage outside device backups: iOS `tmp/Export`, deleted when the share
sheet ends; Android `cache/export`, reachable only through a non-exported `FileProvider` (read access is granted
to the chosen recipient only), emptied at the next export and at launch (the Android share sheet never reports
when the receiver has finished reading).

## Import (replace only)

Settings → **Backup and transfer** → **Import a cabinet**, or open a `.medicabinet` file with the app (Files, Mail,
Gmail, Quick Share).

1. The whole file is read and validated first: a damaged, hostile, oversized or unknown file is refused as a
   whole, with one of a few messages (not an archive, damaged, too large, **newer version → update MediCabinet**,
   unreadable). Nothing is logged or shown beyond the error kind.
2. The **preview** shows the file (export date, exporting app and version, format version, counts, boxes with an
   unknown expiry) next to the phone's current cabinet, and states that the cabinet will be **replaced**, not
   merged.
3. **Replace my cabinet** (destructive style) opens a confirmation whose default is Cancel.
4. In **one transaction**: the current cabinet is read, an automatic backup of exactly that cabinet is written and
   read back, every row is deleted, the file's rows are inserted with their ids, fields and dates. Any failure
   rolls everything back. Old backups are pruned only after the commit.
5. Every screen shows the new cabinet without a restart (a cabinet revision signal); the scan session ends.

If the current cabinet holds no box and only the default location, there is nothing to lose: no backup is taken
and the preview says so. Preferences (session location, sound, expiring-soon window) are not in the file; a
preference that pointed at a location that no longer exists falls back to the default.

### Opening a file with the app

- **iOS**: the exported type `com.medicabinet.archive` (extension `medicabinet`) and a document type declared in
  `project.yml`; the file is copied to `Documents/Inbox`, and that copy is deleted when the flow ends and at launch.
- **Android**: intent filters on the app's own MIME type (`application/vnd.medicabinet+json`) and on
  `application/octet-stream` when the path ends in `.medicabinet`. Never `application/json` or `*/*`. A file whose
  extension was lost in transit is not offered: use **Import a cabinet** (the screen says so). The file is read
  immediately, capped, because the URI grant can end with the activity; only `content:` addresses are read.
- A file opened while a form with unsaved input is open waits behind a banner until that form closes. The camera
  stops while the import is shown. If the cabinet storage is unavailable, the file is refused with that reason.

## Automatic backups and restore

Before every import and every restore, the current cabinet is saved as an archive in the app's private,
protected backups folder (iOS `Application Support/MediCabinet/Backups/`, Android `files/backups/`), the same
folder as the pre-migration database copies and covered by the same device backups. Names are
`pre-import-<UTC time>.medicabinet` and `pre-restore-<UTC time>.medicabinet`; the newest **3** are kept
(`ArchiveBackups.defaultKeepCount`), the oldest deleted only after a new one was written and read back.
Pre-migration copies are never touched.

Settings → **Backup and transfer** → **Automatic backups** lists them, newest first, with date, reason and box
count; a file that cannot be read is listed as unreadable, not hidden. **Restore** runs the same flow as an import
(validation, preview, confirmation, backup of the current cabinet, one-transaction replace). A backup is never
shared or sent by itself; to move one, restore it and export.

## Privacy

No permission, no network, no analytics. Export happens only on explicit action after the warning. Logs carry the
stage and the error kind only. Android declares no new permission (`INTERNET` stays absent); iOS needs no new
privacy-manifest entry (dates come from file names, not file-timestamp APIs).

## Where the code is

| | Core (`MediCabinetCore` / `:core`) | Persistence | UI |
|--|-----|-----|-----|
| Format, validation | `Archive/` (reader, writer, validator, one reader per `formatVersion`) | | |
| Use case | `CabinetTransfer`, ports `CabinetArchiveStore`, `ArchiveBackupStore` | | |
| iOS | | `GRDBCabinetArchiveStore`, `FileArchiveBackupStore` | `Features/Transfer/` |
| Android | | `SqlCabinetArchiveStore`, `FileArchiveBackupStore` | `features/transfer/` |

Shared fixtures (the contract): `fixtures/archive/v1/golden.medicabinet`, `archive-cases.json`,
`archive-code-points.json`, `archive-store-contract.json`. Each platform's encoder reproduces the golden file byte
for byte.
