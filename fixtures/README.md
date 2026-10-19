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
| `reference-lookup.json` | GTIN → CIP13 conversion (both cores) and product lookup against the sample database (both apps): name, category, unknown, withdrawn, malformed input |
| `reference-sample.sqlite` | a small reference database (reference schema v1) built by `scripts/tests/write-reference-sample.py` from invented BDPM-shaped files; the app lookup tests open it |
| `reference-sample-manifest.json` | the manifest of the sample database (same format as `reference-data/bundle/reference-manifest.json`): the Android installer tests copy and verify the sample against it |
| `gs1-datamatrix.json` | GS1 DataMatrix / EAN-13 payload parsing: GTIN, expiry (invalid vs missing), lot, serial, the `shape` of a payload, parse error kinds |
| `scan-debounce.json` | `ScanDebouncer`: which sightings count as a new scan (1 s re-scan gap) |
| `scan-intake.json` | `IntakePolicy`: add / complete / confirmAnother / alreadyInCabinet / removedEarlier |
| `product-resolution.json` | `ProductResolver`: name and category from reference data and the previous box |
| `expiry-status.json` | `ExpiryRule` (status + days left from an expiry, today and the window) and how an instant + time zone becomes today (`todayCases`) |
| `expiring-overview.json` | the Expiring view: sections, order, tie-breaks by folded name, badge count |
| `expiry-digest.json` | the monthly reminder: counts per digest day (`cases`) and the next / due digest days (`scheduleCases`) |
| `archive-cases.json` | `ArchiveDecoder`: golden file summary, every error kind with its field path, hostile files (inline, base64 or generated), limits |
| `archive-code-points.json` | the code-point length used by every archive length limit |
| `archive/v<N>/golden.medicabinet` | the canonical archive of `formatVersion` N, kept forever: every later version must import it, and the canonical encoder must reproduce it **byte for byte** |
| `schema-v1.sql` | the exact v1 DDL both platforms run (each platform's test compares its own copy, ignoring whitespace and `--` comments) |
| `schema-v2.sql` | the exact v2 migration step (box tombstone, unique serial among non-deleted boxes), run after v1 on both platforms |
| `schema-v2.json` | the tables, columns and indexes of a database migrated to v2 (`schema-v1.json` is the v1-only state) |
| `schema-v3.sql` | the exact v3 migration step (location tombstone `deletedAt`), run after v2 on both platforms |
| `schema-v3.json` | the tables, columns and indexes of a database migrated to v3 (`schema-v1.json` / `schema-v2.json` are the earlier states) |
| `schema-v1.json` | the tables, columns and indexes every platform's database v1 must have |
| `repository-contract.json` | names of the repository contract checks both platforms run (the checks themselves are code) |
| `text-normalization.json` | `TextNormalizer`: `normalize` (accent/case/ligature/whitespace folding) and `matches` (search: every query word is a substring) — also the comparison for location-name uniqueness and name ordering |
| `location-names.json` | `LocationName.validate`: trim, 1…50 code points, no duplicate (normalized) name among the other locations |
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
| Archive error kinds | `tooLarge`, `notJSON`, `notArchive`, `unsupportedVersion`, `newerVersion`, `invalidStructure`, `invalidValue`, `tooManyItems`, `duplicateId`, `unknownLocation`, `defaultLocation`, `duplicateSerial` |
| Archive platforms | `ios`, `android` |
| Archive file | extension `medicabinet`, MIME `application/vnd.medicabinet+json`, iOS UTType `com.medicabinet.archive` |
| Archive error path | field keys joined by `.` and list indexes as `[n]`, e.g. `boxes[12].expiry`; keys and indexes only, never a value; `null` when no field is involved |

## Adding a fixture

1. Add the JSON file with `formatVersion`, `description`, unique case names.
2. Load it in both suites through `SharedFixtures` (Swift: `MediCabinetCoreTestSupport`;
   Kotlin: `:core` test fixtures).
3. Document it in the table above.
