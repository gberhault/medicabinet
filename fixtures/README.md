# Shared fixtures

Language-neutral test vectors loaded by **both** the Swift (`ios/`) and the Kotlin
(`android/`) test suites. They are how the two native implementations are kept behaviourally
identical: a case added here runs on both platforms with no code change, and a case that one
platform fails fails that platform's gate.

Every file is JSON with:

| Key | Meaning |
|-----|---------|
| `formatVersion` | integer, bumped when the *shape* of the file changes |
| `description` | what the cases mean, in one or two sentences |
| `cases` | list of objects with a unique `name` (files that are not case lists, such as `schema-v1.json`, have their own keys instead) |

`python3 -I scripts/check-fixtures.py` validates the `.json` files (run by `scripts/local-ci.sh`); `.sql` files are plain text.

## Files

| File | Used for |
|------|----------|
| `expiry-dates.json` | `ExpiryDate` validation, `lastValidDay`, `sortKey`, error kinds |
| `categories.json` | the category taxonomy in display order; which categories the reference-data build may assign and which the user may pick |
| `reference-lookup.json` | GTIN → CIP13 conversion (both cores) and product lookup against the sample database (both apps): name, category, `cis`, `isWithdrawn`, unknown, withdrawn, malformed input |
| `reference-sample.sqlite` | a small reference database (reference schema v2) built by `scripts/tests/write-reference-sample.py` from invented BDPM-shaped files; the app lookup tests open it |
| `reference-sample-manifest.json` | the manifest of the sample database (same format as `reference-data/bundle/reference-manifest.json`): the Android installer tests copy and verify the sample against it |
| `gs1-datamatrix.json` | GS1 DataMatrix / EAN-13 payload parsing: GTIN, expiry (invalid vs missing), lot, serial, the `shape` of a payload, parse error kinds |
| `scan-debounce.json` | `ScanDebouncer`: which sightings count as a new scan (1 s re-scan gap) |
| `scan-intake.json` | `IntakePolicy`: add / complete / confirmAnother / alreadyInCabinet / removedEarlier |
| `product-resolution.json` | `ProductResolver`: name and category from reference data, the named product of the code and the previous box (the previous box only carries a user category over for a code the reference knows) |
| `product-memory.json` | `MemoryPolicy`: when a box save (new box, box edit, session correction) remembers its name and category as the named product of its code |
| `named-product-backfill.json` | `NamedProductBackfill`: the named products created once after the schema v4 upgrade, from the newest box of every code the reference does not know |
| `expiry-status.json` | `ExpiryRule` (status + days left from an expiry, today and the window) and how an instant + time zone becomes today (`todayCases`) |
| `expiring-overview.json` | the Expiring view: sections, order, tie-breaks by folded name, badge count |
| `expiry-digest.json` | the monthly reminder: counts per digest day (`cases`) and the next / due digest days (`scheduleCases`) |
| `archive-cases.json` | `ArchiveDecoder`: golden file summaries (v1 and v2), every error kind with its field path, hostile files (inline, base64 or generated), limits; see "Archive fixtures" below |
| `archive-code-points.json` | the code-point length used by every archive length limit |
| `archive/v<N>/golden.medicabinet` | the canonical archive of `formatVersion` N, kept forever: every later version must import it. The encoder writes the **current** version only, so it must reproduce the golden file of the current version (v2) **byte for byte**; the older golden files (v1) are only decoded and never rewritten |
| `schema-v1.sql` | the exact v1 DDL both platforms run (each platform's test compares its own copy, ignoring whitespace and `--` comments) |
| `schema-v2.sql` | the exact v2 migration step (box tombstone, unique serial among non-deleted boxes), run after v1 on both platforms |
| `schema-v2.json` | the tables, columns and indexes of a database migrated to v2 (`schema-v1.json` is the v1-only state) |
| `schema-v3.sql` | the exact v3 migration step (location tombstone `deletedAt`), run after v2 on both platforms |
| `schema-v3.json` | the tables, columns and indexes of a database migrated to v3 (`schema-v1.json` / `schema-v2.json` are the earlier states) |
| `schema-v4.sql` | the exact v4 migration step (`namedProduct`, `cabinetMeta` and the backfill marker), run after v3 on both platforms |
| `schema-v4.json` | the tables, columns and indexes of a fully migrated database (v4); `schema-v1.json` … `schema-v3.json` are the earlier states |
| `schema-v1.json` | the tables, columns and indexes every platform's database v1 must have |
| `archive-store-contract.json` | names of the `CabinetArchiveStore` contract checks both platforms run (the checks themselves are code). Besides `snapshot` / `replace`, the contract reads the named-product fill marker (`backfillPending`, the `cabinetMeta` key `namedProductBackfill`) and checks: a snapshot holds the named products, forgotten ones too, and always `namedProductsNeedFill` false; `replace` swaps them in the same transaction (delete order `namedProduct`, `box`, `location`); a replace of a cabinet with `namedProductsNeedFill` writes the marker `pending`, any other replace deletes it; a throwing hook changes neither products nor marker; two live products with one gtin (or a repeated product id) are refused with `RepositoryError.duplicate` before anything changes, while a live plus forgotten pair or several forgotten ones with one gtin are kept. The "empty store" checks also assert no named product |
| `repository-contract.json` | names of the repository contract checks both platforms run (the checks themselves are code) |
| `text-normalization.json` | `TextNormalizer`: `normalize` (accent/case/ligature/whitespace folding) and `matches` (search: every query word is a substring) — also the comparison for location-name uniqueness and name ordering |
| `location-names.json` | `LocationName.validate`: trim, 1…50 code points, no duplicate (normalized) name among the other locations |
| `short-names.json` | `ShortName.of`: a medicine's name cut before its first ", " for the Cabinet pills (display only) |
| `leaflet-links.json` | `LeafletLink.address`: the public BDPM page of a medicine built from its CIS (8 ASCII digits, not withdrawn), else no link |
| `category-order.json` | `CategoryOrder.alphabetical`: the order of the Cabinet's category cards — A–Z by the localized label (normalized text, by code point), then `other`, then `uncategorized`; includes the shipped French and English labels |
| `cabinet-rows.json` | `CabinetRows.build`: the Cabinet list — product rows under category sections, row status (most urgent box), ordering, location / status / search filters |
| `scan-removal.json` | `RemovalPolicy` / `ScanRemoval`: what a scan in Remove mode does — remove, choose among candidates, alreadyRemoved, notInCabinet |

## Names that cross platforms

These strings are identical on both platforms (storage values, error kinds, export keys):

| Concept | Values |
|---------|--------|
| Expiry error kinds | `invalidYear`, `invalidMonth`, `invalidDay` |
| `RemovalReason` | `usedUp`, `thrownAway` |
| `ExpiryStatus` | `expired`, `expiringSoon`, `ok`, `unknown` |
| `CategoryID` (taxonomy, display order) | `pain-fever`, `cold-ent`, `digestion`, `allergy`, `skin-wound`, `eyes`, `muscles-joints`, `mouth-teeth`, `vitamins-minerals`, `first-aid`, `other`, `uncategorized` |
| `ReferenceUnavailableReason` | `missing`, `unreadable`, `unsupportedSchema` |
| Parse error kinds | `empty`, `tooLong`, `invalidCharacters`, `missingGTIN`, `invalidGTIN` |
| Unreadable scanned expiry | `invalidMonth`, `invalidDay` (as `ExpiryDate`), `invalidFormat` (AI 17 is not six digits) |
| Intake errors | `invalidName`, `invalidCode` |
| Intake decisions | `add`, `complete`, `confirmAnother`, `alreadyInCabinet`, `removedEarlier` |
| Intake missing fields | `name`, `expiry` |
| Barcode symbologies | `dataMatrix`, `ean13` |
| Repository errors | `notFound`, `unknownLocation`, `duplicate`, `duplicateDefault`, `duplicateSerial`, `duplicateName`, `defaultLocation`, `locationNotEmpty` |
| Location-name errors | `empty`, `tooLong`, `duplicateName` |
| Box edit errors | `emptyName`, `nameTooLong`, `categoryNotSelectable` |
| Removal decisions | `remove`, `choose`, `alreadyRemoved`, `notInCabinet` |
| Expiring-soon window choices (days) | `30`, `60`, `90`; default `60` |
| Digest day and time | the `1`st of the month at `09:00` local (540 minutes) |
| Archive error kinds | `tooLarge`, `notJSON`, `notArchive`, `unsupportedVersion`, `newerVersion`, `invalidStructure`, `invalidValue`, `tooManyItems`, `duplicateId`, `unknownLocation`, `defaultLocation`, `duplicateSerial`, `duplicateNamedProduct` |
| Archive platforms | `ios`, `android` |
| Archive file | extension `medicabinet`, MIME `application/vnd.medicabinet+json`, iOS UTType `com.medicabinet.archive` |
| Archive error path | field keys joined by `.` and list indexes as `[n]`, e.g. `boxes[12].expiry`; keys and indexes only, never a value; `null` when no field is involved |

## Archive fixtures (`archive-cases.json`, `archive/v<N>/`)

- **Case input**: `file` (path under `fixtures/`), `content` (inline text), `contentBase64` or `generate`. `generate` is
  `{fileBytes: n}` (n spaces) or `{locations, boxes}` (a synthetic valid **formatVersion 1** archive), or
  `{locations, boxes, namedProducts}` (a synthetic valid **formatVersion 2** archive, `namedProducts` possibly 0).
  Synthetic archives: location `i` has id `1<%07X i>-0000-4000-8000-<%012X i>`, name `Location i`, `isDefault` only for
  `i = 0`, `sortOrder` i; every box has id `B<%07X i>-…` (same pattern), gtin `03400936404564`, name `Box i`,
  serial `SN<i>`, location 0, expiry 2027-03-15; named product `i` has id `C<%07X i>-…`, gtin `%014d` of i,
  name `Named i`, category `uncategorized`, `categoryOverridden` false, not forgotten. Dates are all
  `2026-10-02T10:00:00.000Z` (boxes, products) or `2026-10-01T09:00:00.000Z` (locations).
- **Expectation** (`expect`): `{summary, needsBackup}` or `{error: {kind, path}}`. `summary` holds `formatVersion`
  (the version of the file), `sourceFormatVersion` (the same: the decoded archive remembers the version it came from),
  `exportedAt`, `app`, then the counts of the decoded cabinet: `locations` (not deleted), `inCabinet`, `removed`,
  `deleted`, `unknownExpiry`, **`namedProducts`** (named products that are not forgotten, i.e. `deletedAt` is null) and
  **`namedProductsNeedFill`** (true exactly for a formatVersion 1 file, which has no named products: after the import
  the app fills them from the imported boxes; false for version 2, whatever the list holds). `needsBackup` is true when
  an import would destroy something: more than one location (deleted ones not counted), any box (removed and deleted
  ones included), or **any named product, forgotten ones included** (so a cabinet holding only a forgotten product
  has `namedProducts` 0 and `needsBackup` true).
- **v1 cases** keep their input files and codes. They only gain `namedProducts: 0` and `namedProductsNeedFill: true`
  in their summary. The two "newer version" cases were written with `formatVersion` 2, which is no longer newer:
  they now use 3, to stay a version this app cannot read.
- **v2 cases** (the case names starting with "version 2", "golden file version 2", and every "named product" /
  "named products" case): the golden file; accepted shapes (live plus forgotten product with one gtin in either order,
  several forgotten ones with one gtin, any order of the list, ids shared with a box or a location, lower-case uuid,
  name of 200 code points, category of 100 code points); refusals: a missing / non-array `namedProducts`
  (`invalidStructure` at `namedProducts`), the same member inside a version 1 file (an unknown key there),
  a missing / unknown / repeated product key (`invalidStructure` at `namedProducts[i].<key>`), a bad uuid, date, gtin,
  name or category (`invalidValue`; a JSON `null` or a wrong JSON type is `invalidStructure`), a repeated id
  (`duplicateId` at `namedProducts[i].id`, ids compared ignoring case), two live products with one gtin
  (`duplicateNamedProduct` at `namedProducts[i].gtin`, `i` being the **second** live one), and more than
  `maxNamedProducts` = 20 000 (`tooManyItems` at `namedProducts`, via `generate`).
- **Check order for a product**: per product in list order: gtin, name, category, createdAt, updatedAt, deletedAt (each
  `invalidValue`); then, over the whole file after the boxes' rules: unique ids, then one live product per gtin.
  A field fault anywhere wins over a row-to-row rule.
- **Golden files**: `archive/v1/golden.medicabinet` is never edited. `archive/v2/golden.medicabinet` is v1's cabinet plus
  one more box and three named products (a forgotten "Test" and a live "Produit 2" sharing a gtin, and a live
  "Crème solaire SPF 50" with a category the user chose). The decoder reads both; the encoder reproduces the v2 file
  byte for byte, including the ordering of `namedProducts` by `createdAt` then upper-case id.

## Adding a fixture

1. Add the JSON file with `formatVersion`, `description`, unique case names.
2. Load it in both suites through `SharedFixtures` (Swift: `MediCabinetCoreTestSupport`;
   Kotlin: `:core` test fixtures).
3. Document it in the table above.
