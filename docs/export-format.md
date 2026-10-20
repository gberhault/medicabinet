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
| Current `formatVersion` | **2** (the apps write version 2 and read versions 1 and 2) |
| Golden files (kept forever) | [`fixtures/archive/v1/golden.medicabinet`](../fixtures/archive/v1/golden.medicabinet), [`fixtures/archive/v2/golden.medicabinet`](../fixtures/archive/v2/golden.medicabinet) |
| Decode cases (both platforms run them) | [`fixtures/archive-cases.json`](../fixtures/archive-cases.json) |

| `formatVersion` | Added | Written by |
|-----------------|-------|------------|
| 1 | locations and boxes | the first versions with export (iOS and Android) |
| 2 | `namedProducts` ([the products you named](named-products.md)) | every app version from the named-products release on |

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

### Canonical form (every version)

The writer always produces the same bytes for the same cabinet, on both platforms: keys in the order above,
every non-empty object and array over several lines with a two-space indent, `": "` after keys, LF line
endings, `[]` for an empty array and a final newline; locations ordered by `sortOrder` then id, boxes by
`createdAt` then id (ids upper-case); strings escape only `"`, `\`, `\b \f \n \r \t` and the other control
characters below U+0020 (as `\u00xx`), everything else is raw UTF-8. Both suites check that encoding the
golden file's content gives the golden file back, byte for byte.

## Format version 2

Version 2 is version 1 plus one root member, `namedProducts`, after `boxes`. Every rule of version 1 above applies
unchanged (the header, the date form, `locations`, `boxes`, the canonical form, the limits); only the differences are
listed here.

```json
{
  "archive": "medicabinet",
  "formatVersion": 2,
  "exportedAt": "2026-10-21T08:15:30.123Z",
  "app": { "name": "MediCabinet", "platform": "ios", "version": "0.7.0", "build": "10" },
  "locations": [ … ],
  "boxes": [ … ],
  "namedProducts": [
    { "id": "C0000003-0000-4000-8000-000000000003", "gtin": "03401234567890", "name": "Crème solaire SPF 50",
      "category": "skin-wound", "categoryOverridden": true,
      "createdAt": "2026-10-02T10:00:13.000Z", "updatedAt": "2026-10-08T19:30:00.500Z", "deletedAt": null }
  ]
}
```

A **named product** is the name (and category) the household gave to a code the bundled medicines database does not
name. Every key is required (`null` explicit for `deletedAt`), and unknown or repeated keys are refused, as in
version 1. `namedProducts` is required: a version 2 header over a version 1 body is refused (`invalidStructure` at
`namedProducts`), and a version 1 file may not carry it (it is an unknown key there).

| Field | Rule |
|-------|------|
| `namedProducts` | 0–20 000 entries, forgotten ones included (the same limit family as `boxes`); more is `tooManyItems` at `namedProducts` |
| `id` | UUID, 8-4-4-4-12 hexadecimal digits in either case; unique among named products (a named product may share an id with a box or a location). Compared ignoring case |
| `gtin` | exactly 14 ASCII digits (never `null`, unlike `box.gtin`; the check digit is not verified) |
| `name` | 1–200 code points |
| `category` | 1–100 code points; free text like `box.category`, an id this app version does not know is kept as written |
| `categoryOverridden` | boolean: the category was chosen by the user (else it is `uncategorized` and the flag is `false`) |
| `createdAt`, `updatedAt` | the v1 date form |
| `deletedAt` | `null` for a live product, else the date the household **forgot** it. A forgotten product keeps its row (a tombstone, so a later sync can tell "forgotten" from "never existed") and stops naming its code; the boxes it named are untouched |
| (`gtin`) | at most one **live** (`deletedAt` null) product per gtin, like the database's partial unique index. A live and a forgotten one, or several forgotten ones, may share a gtin |

Canonical order: `namedProducts` after `boxes`, ordered by `createdAt` then id (ids upper-case); keys in the order of the
example (`id`, `gtin`, `name`, `category`, `categoryOverridden`, `createdAt`, `updatedAt`, `deletedAt`); `[]` when empty.
The decoder accepts any order. The encoder writes version 2 only.

**Importing version 1.** A version 1 file stays importable by every later app version. It has no named products, so
after the import the app fills them from the imported boxes, exactly as after an upgrade
([`named-products.md`](named-products.md), *After the upgrade*): the store records a *pending* marker with the replaced
cabinet, and the background fill runs once. Importing a version 2 file replaces the named products with the file's and
clears that marker (the file is authoritative, even when its list is empty). Automatic backups are archives, so they
carry the named products.

**An app that reads only version 1** refuses a version 2 file as `newerVersion`.

## Reading an untrusted file

The file is treated as hostile: it is validated as a whole before anything is shown or changed, and a file
that fails any rule is refused as a whole. Whatever the bytes, the decoder returns an archive or an error
kind, never anything else and never a crash (truncations, byte mutations and random bytes are tested on both
platforms).

| Limit (named defaults) | Value |
|------------------------|-------|
| File size | 32 MiB |
| Boxes / locations / named products | 20 000 / 200 / 20 000 |
| A decoded string | 4 096 bytes |

Order of the checks: size, valid UTF-8 (a leading BOM is skipped), the first two keys, the version, the rest of
the file in order (the first fault wins), then the rules between rows (unique ids, default location, boxes in
known locations, unique serials, then for version 2 unique named-product ids and one live named product per GTIN).
The decoder never builds a tree: a value the format does not expect is refused as soon as its key is seen, so deeply
nested input is never read.

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
| `tooManyItems` | more locations, boxes or named products than the limit |
| `duplicateId`, `unknownLocation`, `defaultLocation`, `duplicateSerial` | the rules between rows above |
| `duplicateNamedProduct` | version 2: two live named products with the same `gtin` (path `namedProducts[i].gtin`, `i` the second one) |

## Adding a version

1. Add the fixture first: `fixtures/archive/v<N>/golden.medicabinet` and the new decode cases.
2. Append a reader for the new version to `ArchiveDecoder.readers` on both platforms (the older readers are
   never edited) and migrate what it reads into the current model there.
3. The old golden files keep their tests: every later version imports them, and the suite fails if a version
   has no golden file.
4. Document the version on this page.
