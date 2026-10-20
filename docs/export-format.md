# Export file format (`.medicabinet`)

One file holds the whole cabinet. It is a single UTF-8 JSON document, written and read by both apps
(an iPhone cabinet can move to an Android phone and back). It contains **health data** and is **not
encrypted**: the apps only write it on an explicit user action and warn before sharing it.

This page is the public contract. Every `formatVersion` ever shipped stays importable by every later app
version, and its definition is never edited: a change adds a new version, a reader for it and a migration.
The rules are written once as shared fixtures under `fixtures/` and implemented in both cores
(`MediCabinetCore`, Kotlin `:core`); see [`specs/export-import/`](../specs/export-import/) for the reasoning.

| | |
|--|--|
| Extension / MIME / iOS UTType | `.medicabinet` · `application/vnd.medicabinet+json` · `com.medicabinet.archive` |
| Current `formatVersion` | **1** |
| Golden file (kept forever) | [`fixtures/archive/v1/golden.medicabinet`](../fixtures/archive/v1/golden.medicabinet) |
| Decode cases (both platforms run them) | [`fixtures/archive-cases.json`](../fixtures/archive-cases.json) |

## Format version 1

```json
{
  "archive": "medicabinet",
  "formatVersion": 1,
  "exportedAt": "2026-10-19T08:15:30.123Z",
  "app": { "name": "MediCabinet", "platform": "ios", "version": "0.5.0", "build": "6" },
  "locations": [ { "id": "…", "name": "Salle de bain", "isDefault": true, "sortOrder": 0,
                   "createdAt": "…", "updatedAt": "…", "deletedAt": null } ],
  "boxes": [ { "id": "…", "gtin": "03400936404564", "productName": "…", "category": "pain-fever",
               "categoryOverridden": false, "locationId": "…",
               "expiry": { "year": 2027, "month": 4, "day": null },
               "lot": null, "serial": null, "isOpened": false,
               "removal": { "reason": "usedUp", "at": "…" },
               "createdAt": "…", "updatedAt": "…", "deletedAt": null } ]
}
```

Every key is required; an absent value is an explicit `null`. Unknown or repeated keys are refused. The first two
keys must be `archive` and `formatVersion`, in that order; the rest may come in any order.

| Field | Rule |
|-------|------|
| `exportedAt`, every `createdAt` / `updatedAt` / `deletedAt` / `removal.at` | `yyyy-MM-ddTHH:mm:ss.SSSZ`, UTC, exactly 24 characters, year 0000–9999 |
| `app.name` / `version` / `build` | 1–50 / 1–20 / 1–20 code points; `platform` is `ios` or `android` |
| `locations` | 1–200 entries, deleted ones included; exactly one `isDefault`, and it is not deleted |
| `location.id`, `box.id`, `box.locationId` | UUID, 8-4-4-4-12 hexadecimal digits in either case; unique among locations and, separately, among boxes |
| `location.name` | 1–100 code points |
| `location.sortOrder` | 32-bit signed integer |
| `location.deletedAt` | tombstone of a deleted location (its row stays because removed and deleted boxes point at it) |
| `boxes` | 0–20 000 entries: boxes in the cabinet, removed boxes and deleted boxes (tombstones) |
| `box.gtin` | `null` or exactly 14 ASCII digits (the check digit is not verified) |
| `box.productName` | 1–200 code points |
| `box.category` | 1–100 code points; an id this app version does not know is kept as written |
| `box.expiry` | `null` (unknown, never "OK") or `year` 2000–2099, `month` 1–12, `day` `null` (month precision, GS1 `DD=00`) or a valid day. An invalid date makes the file invalid: it is never corrected or turned into "unknown" |
| `box.lot`, `box.serial` | `null` or 1–20 code points |
| `box.removal` | `null` or `{ reason: "usedUp" \| "thrownAway", at }` |
| `box.deletedAt` | `null`, or the tombstone of a box added by mistake |
| `box.locationId` | a location of the file; a box still in the cabinet (neither removed nor deleted) may not be in a deleted location |
| (`gtin`, `serial`) | unique among boxes that are not deleted and have a GTIN, like the database's unique index |

Lengths are counted in Unicode code points (like SQLite's `length()`), not grapheme clusters or UTF-16 units.
Values derived from other fields (the expiry sort key) are not stored; app preferences, backups, logs and
reference data are not part of the file.

### Canonical form

The writer always produces the same bytes for the same cabinet, on both platforms: keys in the order above,
every non-empty object and array over several lines with a two-space indent, `": "` after keys, LF line
endings, `[]` for an empty array and a final newline; locations ordered by `sortOrder` then id, boxes by
`createdAt` then id (ids upper-case); strings escape only `"`, `\`, `\b \f \n \r \t` and the other control
characters below U+0020 (as `\u00xx`), everything else is raw UTF-8. Both suites check that encoding the
golden file's content gives the golden file back, byte for byte.

## Reading an untrusted file

The file is treated as hostile: it is validated as a whole before anything is shown or changed, and a file
that fails any rule is refused as a whole. Whatever the bytes, the decoder returns an archive or an error
kind, never anything else and never a crash (truncations, byte mutations and random bytes are tested on both
platforms).

| Limit (named defaults) | Value |
|------------------------|-------|
| File size | 32 MiB |
| Boxes / locations | 20 000 / 200 |
| A decoded string | 4 096 bytes |

Order of the checks: size, valid UTF-8 (a leading BOM is skipped), the first two keys, the version, the rest of
the file in order (the first fault wins), then the rules between rows (unique ids, default location, boxes in
known locations, unique serials). The decoder never builds a tree: a value the format does not expect is
refused as soon as its key is seen, so deeply nested input is never read.

### Error kinds

Identical on both platforms (they are in [`fixtures/README.md`](../fixtures/README.md)). Each error also carries a
path made of keys and indexes only (`boxes[12].expiry`), never a value, so it can be logged safely.

| Kind | When |
|------|------|
| `tooLarge` | the file or the archive it would produce is over the size limit |
| `notJSON` | invalid UTF-8 or a JSON syntax fault |
| `notArchive` | a root that is not an object, or a header that is not `archive` = `medicabinet` then an integer `formatVersion` |
| `unsupportedVersion` / `newerVersion` | `formatVersion` below 1 / above what this app reads (update the app) |
| `invalidStructure` | a missing, unknown or repeated key, or a value of the wrong JSON type |
| `invalidValue` | a value that cannot be read or is out of range (UUID, date, expiry, lengths, reason, …) |
| `tooManyItems` | more locations or boxes than the limit |
| `duplicateId`, `unknownLocation`, `defaultLocation`, `duplicateSerial` | the rules between rows above |

## Adding a version

1. Add the fixture first: `fixtures/archive/v<N>/golden.medicabinet` and the new decode cases.
2. Append a reader for the new version to `ArchiveDecoder.readers` on both platforms (the older readers are
   never edited) and migrate what it reads into the current model there.
3. The old golden files keep their tests: every later version imports them, and the suite fails if a version
   has no golden file.
4. Document the version on this page.
