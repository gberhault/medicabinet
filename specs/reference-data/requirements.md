# Reference data and automatic classification — Requirements

Status: **Draft for review** — frame approved 2026-10-19
Epic: #7 · Children: #8, #9, #10, #11 · Branch: `feature/7-reference-data` · Platforms: iOS + Android

## Introduction

A scanned box carries a product code (CIP13 inside its GTIN) but no readable name. Without
reference data, every box scanned by the bulk-scan epic (#12) would ask the user to type a
name and pick a category, which defeats fast intake. This epic bundles the French public
medicines database (BDPM) in both apps as a compact, read-only reference database, built by a
script. The script also files each product into one of twelve household categories, and keeps
products that BDPM drops two years after they leave the market, because old boxes stay in home
cabinets. Both apps get an offline lookup from GTIN to name and category behind the
`ProductLookup` port that bulk-scan already uses. The facts behind these choices are in
[`docs/reference-data.md`](../../docs/reference-data.md) (spike #8).

> We are building a bundled, offline, cumulative BDPM reference database, classified into
> twelve household categories when it is built, plus a GTIN/CIP13 lookup on iOS and Android,
> so that a scanned box gets its name and category automatically. We are **not** building an
> online refresh, re-classifying boxes already in the cabinet, a category-editing screen
> (#20), new box storage (the per-box override already exists), or showing any medical
> information from BDPM.

### Decisions taken in the challenge (2026-10-19)

1. **Classification at build time.** A reviewed rules file is applied by the build script and
   the category is stored in the reference database. The app cores do not classify; they only
   apply the user's override (`ProductResolver`, bulk-scan). `CLAUDE.md` is amended to say so.
2. **Cumulative reference database.** Each build merges the new BDPM snapshot into the
   previously committed reference database; products missing from the new snapshot are kept and
   marked withdrawn.
3. **Taxonomy of twelve categories** (C1).
4. **Override stays per box.** `box.category` + `box.categoryOverridden` (schema v1) are enough,
   and bulk-scan already passes a user-set category on to the next box of the same GTIN. No
   schema change, no export-format impact.
5. **This epic owns the `ProductLookup` port** with the exact signature of
   `specs/bulk-scan/design.md` §3.3 and merges into `develop` before bulk-scan. Bulk-scan then
   rebases, drops its own copy, and binds the real lookup (its task T29).

## Glossary

- **BDPM** — Base de Données Publique des Médicaments, published monthly as tab-separated files
  under the Licence Ouverte v1.0.
- **CIS** — BDPM product identifier (one medicine: name, form, routes, substances).
- **CIP13** — 13-digit presentation code (one box type), prefix `3400`. A GS1 DataMatrix holds
  it as GTIN-14 `0` + CIP13; a 1D EAN-13 holds the CIP13 itself.
- **Snapshot** — one download of the BDPM files, identified by their publication dates and
  SHA-256.
- **Reference database** — the SQLite file built from snapshots and bundled read-only in both
  apps. Separate from the cabinet database; it never holds household data.
- **Manifest** — the JSON file next to the reference database: data date, snapshot hashes,
  counts, rules version, reference-schema version.
- **Rules file** — the reviewed, versioned list of classification rules applied at build time.
- **Withdrawn product** — a product that was in an earlier snapshot and is missing from the
  latest one.
- **Golden list** — the product-owner-reviewed list of real household products with their
  expected category; the classifier's acceptance test.

## User stories and acceptance criteria

Requirement IDs: **S** spike, **B** build script, **C** taxonomy and classification,
**L** lookup, **A** attribution, **X** cross-cutting. Child issues: S → #8, B → #9, C → #10,
L → #11.

### S — Dataset facts and licence (#8)

*As the product owner, I want the dataset's real content and reuse terms written down, so
that every later decision rests on checked facts.*

1. `docs/reference-data.md` SHALL record the licence and its obligations, the files used and
   their columns, per-file encodings and line endings, counts and SHA-256 of the analysed
   snapshot, the two-year withdrawal window, what BDPM does not cover, the ATC coverage, and the
   decisions taken from them.
2. WHEN a later snapshot contradicts a recorded fact (new column, different encoding), the
   build SHALL fail (B3) and the page SHALL be updated before the data is accepted.

### B — Build script: BDPM → bundled reference database (#9)

*As the maintainer, I want one command that turns the BDPM files into the database both apps
bundle, so that updating the data is routine, reviewable and reproducible.*

1. A build script (Python 3, standard library only) SHALL take a directory of BDPM files, the
   previously committed reference database (absent on the first build), the rules file and the
   taxonomy, and SHALL write a new reference database and its manifest.
2. The reference database SHALL hold only what the apps need: CIP13, product name, category,
   and the withdrawn state with its date. It SHALL be indexed for lookup by CIP13 and carry a
   reference-schema version.
3. The script SHALL treat its input as untrusted and SHALL fail with a clear message, writing
   nothing, WHEN a file is missing, cannot be decoded in its expected encoding, has an
   unexpected number of columns, or holds a CIP13 that is not 13 digits with prefix `3400`.
   A name or label longer than a named limit (255 characters, the longest observed) SHALL
   fail the build.
4. Each file SHALL be decoded with its own encoding (`CIS_CIP_bdpm.txt` UTF-8; the others
   Windows-1252), with CRLF and LF both accepted.
5. Product names SHALL be BDPM's product denomination (`CIS_bdpm`, column 2), trimmed of
   surrounding whitespace and otherwise unaltered.
6. A presentation whose CIS is missing from `CIS_bdpm` SHALL be skipped and counted in the
   build report.
7. Merge with the previous reference database:
   1. a CIP13 in the new snapshot SHALL take the snapshot's name and category, and SHALL be
      marked not withdrawn (also when it was withdrawn before);
   2. a CIP13 in the previous database but not in the snapshot SHALL be kept with its last name
      and category, marked withdrawn, with the data date of the first snapshot it was missing
      from;
   3. WHEN the snapshot's data date is older than the previous database's, the build SHALL
      fail;
   4. WHEN the snapshot holds fewer than 95 % of the previous database's non-withdrawn CIP13,
      the build SHALL fail unless an explicit override flag is passed (protects against a
      truncated download).
8. The build SHALL be deterministic: the same inputs SHALL produce a reference database with
   identical content and an identical manifest (no build timestamp).
9. The script SHALL print a build report: counts of products and CIP13, added, withdrawn,
   reinstated, renamed, recategorized, skipped, and per-category totals, so the data-update
   commit can be reviewed.
10. The manifest SHALL record the data date (the latest publication date of the files used),
    the SHA-256 of each input file, the rules-file version, the reference-schema version, the
    counts, and the SHA-256 of the reference database.
11. The script SHALL fail WHEN the reference database exceeds a named size budget, set in the
    design from a measured build and consistent with the < 50 MB app-size target.
12. The script SHALL NOT download anything by default; an explicit download option, if
    provided, SHALL use HTTPS to the official BDPM host only and record the SHA-256 of what it
    fetched. Raw BDPM files SHALL NOT be committed; the reference database and manifest SHALL be.
13. The script SHALL have its own tests on small BDPM-shaped files (both encodings, CRLF/LF,
    malformed lines, merge cases, determinism), run by the shared checks of
    `scripts/local-ci.sh`.
14. The gate SHALL fail WHEN the committed reference database does not match its manifest's
    SHA-256, or WHEN the copies bundled by the two apps differ from the committed one.

### C — Taxonomy and classification (#10)

*As a household member, I want each box filed into a familiar category without sorting it
myself, so that I find what I need and see what is missing — and I want to change it when the
app gets it wrong.*

1. The taxonomy SHALL be exactly these twelve categories, in this display order, with stable
   identifiers listed in `fixtures/README.md`:

   | ID | French | English |
   |----|--------|---------|
   | `pain-fever` | Douleur et fièvre | Pain and fever |
   | `cold-ent` | Nez, gorge, oreilles | Nose, throat and ears |
   | `digestion` | Digestion | Digestion |
   | `allergy` | Allergies | Allergies |
   | `skin-wound` | Peau et plaies | Skin and wounds |
   | `eyes` | Yeux | Eyes |
   | `muscles-joints` | Muscles et articulations | Muscles and joints |
   | `mouth-teeth` | Bouche et dents | Mouth and teeth |
   | `vitamins-minerals` | Vitamines et minéraux | Vitamins and minerals |
   | `first-aid` | Trousse de secours | First-aid kit |
   | `other` | Autres | Other |
   | `uncategorized` | Non classé | Uncategorized |

2. The taxonomy SHALL be one shared fixture read by the build script and by both cores' tests;
   both cores SHALL expose the same ordered list, and both apps the same labels (French and
   English, checked by the string-parity check).
3. The build script SHALL give every product exactly one category from an ordered rules file,
   first matching rule wins. Rules MAY match on active substances, routes of administration,
   pharmaceutical form and ATC code (when `CIS_MITM` has one).
4. WHEN no rule matches, the product SHALL be `uncategorized`. The build SHALL never assign
   `first-aid` (BDPM holds no dressings or devices; the user picks it manually) and SHALL fail
   WHEN the rules file names a category outside the taxonomy.
5. The rules file SHALL be versioned in the repository and reviewed like code; a change to it
   SHALL show its effect in the build report (C3, B9).
6. A golden list of real household products with their expected category, reviewed by the
   product owner, SHALL be a test of the build script: every entry SHALL classify as expected.
   The golden list SHALL cover every category except `first-aid` and `uncategorized`,
   combination products (e.g. paracetamol + decongestant), the same substance by different
   routes (e.g. diclofenac tablet vs gel), and eye, ear and nose forms of anti-allergy or
   anti-infective substances.
7. Category labels SHALL describe a body area or kind of product, never an indication,
   symptom-to-treatment pairing or advice; the apps SHALL present categories as a tidying aid
   from MediCabinet, not as BDPM or medical information.
8. WHEN a box carries a category ID the running app does not know (for instance from a newer
   app's export), the app SHALL display it as `other` and SHALL keep the stored value unchanged.
9. The list a user picks from SHALL contain every category except `uncategorized`, in display
   order. (The picking screen itself is #20.)
10. A box's category SHALL be decided when it is added, by `ProductResolver` (bulk-scan): the
    user's earlier override for the same GTIN, else the reference category, else
    `uncategorized`. A later reference-data update SHALL NOT change boxes already in the
    cabinet.

### L — Product lookup (#11)

*As a household member, I want a scanned box named and filed instantly, even with no
network, so that intake stays fast — and told plainly when the app does not know the product.*

1. Both cores SHALL declare `ReferenceProduct` and `ProductLookup` with the signature of
   `specs/bulk-scan/design.md` §3.3 (`product(gtin:)` returns the product or nothing for
   "unknown").
2. Converting a GTIN to a CIP13 SHALL be pure core logic and pass every case of a shared
   fixture on both platforms: GTIN-14 `03400…` → drop the leading `0`; any other GTIN
   (including ACL `3401…` parapharmacy, foreign products, malformed input) → unknown, with no
   database query.
3. WHEN the CIP13 is in the reference database (withdrawn or not), the lookup SHALL return its
   name and category; otherwise it SHALL return unknown. It SHALL never return a partial or
   guessed match (no fuzzy or prefix matching).
4. Each app SHALL implement the port over the bundled reference database, opened read-only,
   with every query parameterized and run off the main thread.
5. Both platforms SHALL return the same results for the same GTINs on a sample reference
   database built by the script from test files (shared lookup fixture).
6. A lookup SHALL take < 50 ms on the full reference database (performance assertion with the
   CLAUDE.md method: generous budget, several runs).
7. WHEN the bundled reference database is missing, corrupt, of an unsupported reference-schema
   version, or fails its integrity check, the app SHALL keep working with every product
   unknown, SHALL record only the failure kind in the log, and SHALL show "reference data
   unavailable" in About. It SHALL never crash or block the cabinet.
8. On Android, WHEN the reference database must be copied out of the APK to be opened, the copy
   SHALL go to internal storage, SHALL be atomic (temporary file then rename), SHALL be checked
   against the manifest's SHA-256, and SHALL be replaced only when the bundled version changes.
9. No screen uses the lookup in this epic; bulk-scan binds it (T29). Each app SHALL have an
   integration test that opens the real bundled database and finds a known GTIN.

### A — Attribution (#7)

*As the product owner, I want the data source credited as its licence requires, so that the
app can be distributed and nobody mistakes our categories for official information.*

1. The About screen of both apps SHALL show: the source ("Base de Données Publique des
   Médicaments"), its address, the date of the bundled data (from the manifest, formatted in
   the user's locale), and a statement that MediCabinet is not affiliated with or endorsed by
   ANSM, HAS or the national health insurance and that categories are MediCabinet's own.
2. WHEN the reference data is unavailable (L7), About SHALL say so instead of showing a date.
3. All texts SHALL be in French and English on both platforms with the same wording.

### X — Cross-cutting

1. **Privacy**: no new permission, no network access in the apps (Android keeps no `INTERNET`
   permission), no logging of GTINs, CIP13 or product names. The reference database holds no
   household data.
2. **Storage**: on iOS the reference database is read from the app bundle and never written; on
   Android the copy lives in internal storage and is excluded from Auto Backup (it is
   rebuilt from the APK).
3. **Size**: the app-size increase SHALL be measured on both platforms and stated in the PR
   against the < 50 MB target.
4. **Architecture**: `CLAUDE.md` SHALL be amended — classification is computed by the
   reference-data build, the cores hold the taxonomy, GTIN→CIP13 conversion and override
   resolution — and its Common Task Map SHALL gain "Update the BDPM reference data".
5. **Docs**: `docs/reference-data.md` gains the data-update procedure and the rules-file
   format; `docs/architecture.md` describes the reference database next to the cabinet
   database; `fixtures/README.md` lists the new fixtures and category IDs.
6. **Tests**: success, failure and edge-case tests for each part, on both platforms where
   code exists on both; core coverage targets 100 % of branches, new app code 90 %; the
   script's tests run in the shared gate.
7. **i18n**: every new string in French and English on both platforms, same wording.
8. **Performance**: the reference database SHALL be opened lazily, off the main thread, and
   never on the cold-start path (app start stays < 1 s).

## Out of scope

- Online refresh of the reference data; data updates ship with app releases.
- Re-classifying or renaming boxes already in the cabinet after a data update (possible
  follow-up issue).
- The category picker and box detail (#20), the inventory list by category (#19), binding the
  lookup into the scan flow (#12 T29).
- Parapharmacy (`3401…`), medical devices, food supplements and non-French products: they stay
  unknown and are named and filed by the user.
- Any medical content from BDPM: reimbursement, prices, prescription conditions, HAS opinions,
  safety information, substances shown to the user.
- Presentation details (box size, packaging) in the product name.
- A per-product override table (the per-box override plus bulk-scan's carry-over is enough).

## Open points to settle in the design

1. **Version**: this epic changes only About for the user. Recommendation: iOS and Android
   **0.1.1**, so bulk-scan keeps its planned 0.2.0.
2. **Golden list content** (C6): drafted in the design from real products of the snapshot;
   the product owner reviews it before the rules file is written.
3. **Category labels** (C1): wording above to be confirmed at this review.
4. **Size budget** (B11) and where the reference database lives in the repository and in each
   app bundle.
