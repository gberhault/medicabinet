# Reference data (BDPM)

MediCabinet names and classifies a scanned French medicine from the **Base de Données
Publique des Médicaments** (BDPM, <https://base-donnees-publique.medicaments.gouv.fr>), bundled
in the app so lookup works offline. This page records what the dataset actually contains and
the decisions taken from it (spike #8, epic #7). The build and the classifier are specified in
`specs/reference-data/`.

## Licence and attribution

- **Licence Ouverte / Open Licence v1.0** (Etalab, October 2011), the licence the BDPM download
  page links to (`licence_bdpm.pdf`), applied under art. L.161-40-1 of the French social
  security code. Free reuse, including commercial use, redistribution inside an application,
  and **derived information** (our categories are derived information).
- Obligations:
  1. Cite the source (at least the producer's name, a link is enough) **and the date of its
     last update**.
  2. The citation must not suggest that the reuse is official or endorsed by ANSM, HAS, the
     national health insurance or any public body.
  3. Do not mislead about the content, its source or its update date; the download page adds:
     do not alter the data or change its meaning.
  4. The producers are not liable for reuse of data that is not up to date.
- Consequences for the apps: the About screen shows the source, a link, and the date of the
  bundled data; categories are presented as MediCabinet's own tidying aid, never as BDPM data;
  BDPM text (names) is shown unaltered.

## Files and format (snapshot downloaded 2026-10-19)

Tab-separated, no header, no quoting. Format reference: *Contenu et format des fichiers
téléchargeables dans la BDM* v5 (updated 13/10/2026). The dataset is updated monthly.

| File | Published | Rows | Encoding / line ends | Used for |
|------|-----------|------|----------------------|----------|
| `CIS_bdpm.txt` | 29/09/2026 | 15 883 products (CIS) | Windows-1252, CRLF | name, pharmaceutical form, routes, marketing state |
| `CIS_CIP_bdpm.txt` | 18/10/2026 | 20 900 presentations | **UTF-8**, LF | CIP13 → CIS (the box code) |
| `CIS_COMPO_bdpm.txt` | 29/09/2026 | 32 439 components | Windows-1252, CRLF | active substances (`SA` rows) |
| `CIS_MITM.txt` | 13/10/2026 | 7 855 products | Windows-1252, CRLF | ATC code (subset only, see below) |

SHA-256 of the snapshot:

```
332b95926f76f1a2ab3d70cdf64205397fc13e74b7cc9afad9b625fbb6961640  CIS_bdpm.txt
1b7d2c8cba4e467fffb4b65bb8d731dfb21bdcd32393c07c4c7b4ce075840af0  CIS_CIP_bdpm.txt
90a6e8ee286b6245c3938dd417ac75d1135a61b15ba50cfad70ee6b3bd181b7a  CIS_COMPO_bdpm.txt
300f24c39e84466c617eedce9edfd491870259fd5abd2092b3f8296a3b6245fa  CIS_MITM.txt
```

Findings that the build script must handle:

- **Encodings differ per file** (UTF-8 for presentations, Windows-1252 elsewhere — not
  ISO-8859-1: bytes 0x80–0x9F such as `œ` and `’` occur). Detect per file and fail the build on
  undecodable input.
- Every CIP13 is 13 digits with prefix `3400`; all 20 900 are distinct. A DataMatrix GTIN-14
  `03400…` maps to the CIP13 by dropping the leading `0`; an EAN-13 `3400…` is the CIP13.
- Names (`CIS_bdpm` col. 2) are at most 255 characters (one hits the limit, 200 exceed 120);
  presentation labels likewise (4 hit 255). Truncation by the source is possible.
- 4 presentations point to a CIS missing from `CIS_bdpm`; 1 259 products have no presentation.
- Products in `CIS_bdpm`: 13 650 marketed, 2 233 not marketed; 724 with a revoked authorization.
  Presentations: 3 459 with a declared end of marketing.

## Coverage limits

- **Withdrawn products disappear.** `CIS_bdpm` lists products that are marketed or whose
  marketing stopped **less than two years ago**. Older boxes still sit in home cabinets.
  Decision: each build **merges** the new snapshot into the previously bundled reference
  database; a product absent from the new snapshot is kept and flagged withdrawn.
- **Not in BDPM:** parapharmacy (ACL `3401…`), medical devices (bandages, thermometers) and
  most food supplements. These are "unknown product" → manual name and manual category.
- Homeopathic registrations are included (1 316 products).
- About 2 800 products are injectable-only (hospital use); they still get a name.

## Classification source

- **ATC is not usable as the main source.** It is published only in `CIS_MITM` (medicines of
  major therapeutic interest): 49.5 % of products and 56.7 % of CIP13, but **none of the
  household staples probed** (Doliprane, Efferalgan, Advil, Nurofen, Spasfon, Smecta, Imodium,
  Gaviscon, Maalox, Humex, Strepsils, Hextril, Biafine, Dacryosérum, Voltarène, Arnica).
  Full ATC indexes (WHO) are not openly licensed.
- **Usable inputs for every product:** active substances (`CIS_COMPO`, `SA` rows; 3 937 distinct;
  most products have one), pharmaceutical form (369 values) and routes of administration (68
  values, `;`-separated: `orale`, `cutanée`, `ophtalmique`, `nasale`, `voie buccale autre`,
  `auriculaire`, `transdermique`…). The ATC code is an extra signal where present.
- Decision: classification runs **when the reference database is built**, from a reviewed rules
  file (substance + route/form, ATC when present, one category per product, explicit priority
  for combinations); the category is stored in the bundled database. The app cores only apply
  the user's override (`ProductResolver`).

## Decisions (2026-10-19)

| Topic | Decision |
|-------|----------|
| Classifier location | At reference-DB build time (script), not twin runtime code in the cores |
| Withdrawn products | Kept across builds (cumulative reference DB), flagged withdrawn |
| Taxonomy | 12 categories: `pain-fever`, `cold-ent`, `digestion`, `allergy`, `skin-wound`, `eyes`, `muscles-joints`, `mouth-teeth`, `vitamins-minerals`, `first-aid` (manual only), `other`, `uncategorized` |
| Online refresh | Out of scope; data is updated with app releases and its date shown in About |

## The bundled database

`reference-data/bundle/` holds the two files both apps ship, built by `scripts/build-reference-db.py`:

| File | What it is |
|------|------------|
| `reference.sqlite` | Reference schema v1 (`PRAGMA user_version = 1`), read-only in the apps, about 1.7 MB for 20 896 presentations. Journal mode `DELETE`, no write-ahead file |
| `reference-manifest.json` | Data date, SHA-256 of each BDPM source file, counts (also by category), rules version and `contentSha256` |

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) WITHOUT ROWID;   -- dataDate, rulesVersion
CREATE TABLE presentation (
  cip13          TEXT PRIMARY KEY NOT NULL,   -- 13 digits, 3400…, one row per box type
  name           TEXT NOT NULL,               -- BDPM denomination, trimmed, otherwise unaltered (≤ 255)
  category       TEXT NOT NULL,               -- a category id, or uncategorized
  withdrawnSince TEXT                         -- data date of the first build that no longer had it; NULL = in the latest snapshot
) WITHOUT ROWID;
```

- **Cumulative.** Each build merges the new snapshot into the previous database. A CIP13 missing from the new
  snapshot is kept with its last name and category and dated withdrawn; one that returns is reinstated. Old boxes
  therefore stay identifiable after BDPM drops them (it forgets products two years after they leave the market).
- **`contentSha256`** is the hash of the rows, not of the file (SQLite writes its library version into the file
  header, so the same rows can differ byte for byte). It hashes `user_version`, every meta row sorted by key, then
  every presentation sorted by CIP13, as tab-separated text. The build, the gate (`verify`) and the Android installer
  all compute it; the Kotlin and Python results are checked against each other on the real bundled data.
- Apps and categories: `uncategorized` is the build's "no rule matched". `first-aid` is never assigned by the build
  (BDPM holds no dressings or devices): the user picks it.

## Updating the data (maintainer)

```bash
python3 -I scripts/build-reference-db.py download /tmp/bdpm-2026-11          # four files, HTTPS only, empty directory
# read the publication dates on the BDPM download page: --data-date is the latest of the four
python3 -I scripts/build-reference-db.py build --bdpm /tmp/bdpm-2026-11 --data-date 2026-11-18
python3 -I scripts/build-reference-db.py verify --apps ios,android
```

`build` reads the committed bundle as the previous database, and writes nothing unless everything passed. It fails on
a missing or undecodable file, a wrong column count, a CIP13 that is not `3400` + 9 digits with a valid GS1 check
digit, a name over 255 characters, an older data date than the previous build, a snapshot holding under 95 % of the
presentations that were marketed (a truncated download; `--accept-drop` overrides after a look), a database over
8 MB, or a golden-list case that no longer classifies as expected. It prints a report: counts, what was added,
withdrawn, reinstated, renamed and recategorized (with the first CIP13 of each), and the totals per category before
and after. **Put that report in the commit body** and review it: a surprising number of recategorized products means a
rule changed meaning.

Then bump both apps (the data date is user-visible in About), release, and keep the raw BDPM files out of git.
BDPM publishes no checksums: integrity rests on HTTPS, the validation above, the 95 % guard and the reviewed report.

## Classification rules

`reference-data/rules.json` is an ordered list; the first rule whose conditions all hold gives the category, no match
gives `uncategorized`. Each rule is `{ "name", "when": {…}, "category" }`. Values are compared in upper case without
accents; a substance token matches at the start of a word (`ascorbi` matches `ACIDE ASCORBIQUE`, `cétirizine` does
not match `LÉVOCÉTIRIZINE`).

| Condition | Holds when |
|-----------|-----------|
| `routesAny` | one of the product's routes equals a listed value |
| `routesOnly` | every route is in the list |
| `formAny` | the pharmaceutical form contains a listed text |
| `substancesAny` | an active substance has a listed token at the start of a word |
| `substancesOnly` | every active substance does |
| `atcAny` | the ATC code (from `CIS_MITM`, when present) starts with a listed prefix |
| `procedureAny` | the registration procedure contains a listed text |

The order carries the priorities: route-specific rules before substances (an anti-allergy eye drop is `eyes`, a nasal
spray `cold-ent`), cough and decongestant substances before painkillers and antihistamines (paracetamol with
pseudoephedrine is `cold-ent`), topical anti-inflammatories before `skin-wound` (a diclofenac gel is
`muscles-joints`), and long-term or specialist ATC families before painkillers (antiplatelet aspirin is `other`).
Homeopathy and herbal registrations are `other`. Known limit: a low-dose aspirin without an ATC code in `CIS_MITM` is
filed `pain-fever`; the user can override it.

Categories describe a body area or a kind of product, never an indication; they are MediCabinet's own tidying aid and
must not be presented as BDPM or medical information.

**Golden list** (`reference-data/golden-list.json`): 58 real household products, each with the category the product
owner expects, covering every category the build assigns, combinations, the same substance by different routes, and
eye / ear / nose forms of anti-allergics. `build` refuses a database that breaks one and `verify` re-checks the
committed one. Add a case with every rule you change. The 2026-10-18 build files 882 presentations under
`pain-fever`, 337 `cold-ent`, 862 `digestion`, 175 `allergy`, 597 `skin-wound`, 372 `eyes`, 123 `muscles-joints`, 183
`mouth-teeth`, 361 `vitamins-minerals`, 11 292 `other` (mostly prescription medicines) and 5 712 `uncategorized`.

## In the apps

- **Lookup** (`ProductLookup`, both cores): a GTIN-14 `03400…` with a valid check digit becomes its CIP13 (the leading
  `0` dropped); anything else (ACL `3401…` parapharmacy, foreign GTINs, malformed text) is unknown without a query.
  Found → name and category, withdrawn products included; never a partial or guessed match.
- **When it is used:** the database is installed and opened on first use (a lookup, or opening About), never during
  launch. iOS reads `reference.sqlite` from the app bundle, read-only. Android copies it once from the APK assets to
  `noBackupFilesDir/reference/` (internal, excluded from Auto Backup), atomically, verifying it against the
  manifest's `contentSha256`, and redoes the copy only when the bundled version changes; an installed copy that no
  longer opens is replaced once.
- **When it is unavailable** (missing, unreadable, unsupported schema version): every product is unknown, About says
  so, one log line carries only the reason, and the cabinet keeps working.
- **About** shows the source and address, the date of the bundled data in the user's locale, and that MediCabinet is
  not affiliated with or endorsed by ANSM, HAS or the national health insurance.
- **Category overrides** stay per box (`box.category` + `box.categoryOverridden`, schema v1): bulk-scan's product resolver
  keeps a category the user set for the same GTIN. A later data update never changes boxes already in the cabinet.

Tests: the script's own suite (`scripts/tests/test-build-reference-db.py`), the shared fixtures
`categories.json` / `reference-lookup.json` / `reference-sample.sqlite` (+ manifest) run by both platforms, and the
gate step "shared: reference data" in `scripts/local-ci.sh`.
