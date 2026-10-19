# Reference data and automatic classification — Design

Status: **Approved 2026-10-19**
Epic: #7 · Requirements: [`requirements.md`](requirements.md) (approved 2026-10-19)

How the BDPM files become one read-only database bundled by both apps, how the build
classifies products, and how each app opens and queries it. Section numbers are referenced
from `tasks.md`. Measurements quoted here come from the 2026-10-19 snapshot (spike #8).

## 1. Key decisions

### 1.1 One flat table keyed by CIP13

The apps only ever ask "what is this CIP13?". A single `presentation` table (CIP13 → name,
category, withdrawn date) answers with one primary-key read. A normalized product/presentation
pair was measured too: 1.92 MB vs **1.78 MB** flat (0.36 MB deflated), and the flat table needs
no join. The name and category repeat for the presentations of one product; that costs less than
the join and keeps the cumulative merge (B7) per CIP13, which is what the requirements specify.

### 1.2 A content hash, not a file hash

SQLite writes its library version into the file header, so the same rows built on two machines
can differ byte for byte. The manifest therefore records `contentSha256`: the SHA-256 of a
canonical dump of the rows (§3.3). It is identical for identical inputs on any machine (B8), and
the gate recomputes it from the committed file (B14). Android checks its copy by hashing the
asset stream while copying and comparing it with the written file (§7.2), which does not need a
file hash in the manifest.

### 1.3 One stdlib Python script

`scripts/build-reference-db.py`: one file, standard library only (`csv` is not used — the files
have no quoting — `sqlite3`, `hashlib`, `json`, `unicodedata`, `urllib.request`). It stays one
file because `python3 -I` (the project's rule for running scripts) does not put the script's
directory on `sys.path`, so helper modules could not be imported. Tests load it with
`importlib.util.spec_from_file_location`.

### 1.4 The bundle lives once in the repository

`reference-data/bundle/` holds `reference.sqlite` and `reference-manifest.json`. Both apps
point at that directory: XcodeGen lists the two files as resources of the app target, Gradle
adds the directory as an assets source of `:app`. There is no copy in `ios/` or `android/`, so
the two apps cannot ship different data.

### 1.5 Android copies the database to no-backup storage

Android cannot open SQLite inside the APK. On first use (and when the bundled content hash
changes) the database is copied to `noBackupFilesDir/reference/`. That directory is internal
storage and excluded from Auto Backup and device transfer (X2) by the platform, and the
existing backup rules list only the cabinet anyway. iOS opens the file inside the signed app
bundle, read-only, and never writes it.

### 1.6 Opened lazily, never at launch

Neither app touches the reference database during bootstrap. A `ReferenceData` object opens it
the first time something asks (a lookup, or About asking for the data date), off the main
thread, once. Cold start is unchanged (X8).

### 1.7 The port moves to a `reference` area of the cores

`ReferenceProduct` and `ProductLookup` are declared with bulk-scan's exact signatures, in a new
`Reference/` folder (Swift) and package `com.medicabinet.core.reference` (Kotlin), next to the
taxonomy and the GTIN → CIP13 conversion. When bulk-scan rebases on `develop` after this epic
merges, it deletes its own two declarations from `Scan/ProductResolver.swift` and
`scan/ProductResolver.kt`, and adds `import com.medicabinet.core.reference.*` on Android. The
rest is unchanged (requirements, decision 5).

### 1.8 Deviations found while implementing

- iOS bundles only `reference.sqlite` (it reads the data date from the database's `meta` table); the manifest is
  bundled by Android alone, which needs it to verify the copy (§9).
- The Android installer verifies the copy against the manifest's **content** hash (opening the copy and hashing its
  rows with the build's canonical text, §3.3) rather than a file hash, and replaces an installed copy that no longer
  opens once (`install(force = true)`).
- `fixtures/reference-sample-manifest.json` is added next to the sample database for the installer tests;
  `check-fixtures.py` skips `*-manifest.json` (generated output, not a case list).
- The Android assets source is declared with `sourceSets["main"].assets.directories` (AGP 9).
- `ReferenceUnavailableReason` conforms to `Error` in the Swift core (used as a `Result` failure by the app).

## 2. Repository layout

```
reference-data/
  bundle/
    reference.sqlite             # committed, bundled by both apps (§3)
    reference-manifest.json      # committed, bundled (§3.3)
  rules.json                     # classification rules, reviewed like code (§4.4)
  golden-list.json               # product-owner-reviewed expectations (§5)
scripts/
  build-reference-db.py          # build, download, verify (§4)
  tests/
    test-build-reference-db.py   # unittest suite of the script (§10)
    test-reference-data-break.sh # proves --verify fails on a wrong golden entry or a tampered DB
    reference-data/bdpm/         # tiny BDPM-shaped inputs: both encodings, CRLF/LF, malformed variants
fixtures/
  categories.json                # the taxonomy (§6.1)
  reference-lookup.json          # GTIN → CIP13 and lookup cases (§6.2)
  reference-sample.sqlite        # built by the script from scripts/tests/reference-data/bdpm/
```

## 3. Reference database (reference schema v1)

### 3.1 DDL

```sql
CREATE TABLE meta (
  key   TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL
) WITHOUT ROWID;

CREATE TABLE presentation (
  cip13          TEXT PRIMARY KEY NOT NULL CHECK (length(cip13) = 13 AND cip13 GLOB '3400[0-9]*'),
  name           TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 255),
  category       TEXT NOT NULL,
  withdrawnSince TEXT CHECK (withdrawnSince IS NULL OR withdrawnSince GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')
) WITHOUT ROWID;
```

- `PRAGMA user_version = 1` is the reference-schema version. Apps accept exactly the versions
  they know (today: 1); any other is `unsupportedSchema` (L7).
- `meta` keys: `dataDate` (`YYYY-MM-DD`), `rulesVersion` (integer as text).
- `withdrawnSince` is the data date of the first build whose snapshot no longer had the CIP13;
  `NULL` means it is in the latest snapshot. The apps do not read it today (kept for a future
  "withdrawn" hint and for the merge).
- Built with `page_size = 4096`, `journal_mode = DELETE` (a read-only open must not need a
  `-wal`/`-shm` file), rows inserted in CIP13 order, then `VACUUM`.

### 3.2 Size budget

`MAX_REFERENCE_DB_BYTES = 8_000_000` in the script (B11): 4.5 × the measured 1.78 MB, leaving
room for years of cumulative growth (about 3 500 presentations stop being marketed per two
years). App impact: +0.4 MB download on both platforms (compressed in IPA/APK), +1.8 MB installed
(iOS bundle; Android copy, and the asset stays compressed in the APK).

### 3.3 Manifest

```json
{
  "formatVersion": 1,
  "referenceSchemaVersion": 1,
  "dataDate": "2026-10-18",
  "rulesVersion": 1,
  "sources": {
    "CIS_bdpm.txt":       { "sha256": "…" },
    "CIS_CIP_bdpm.txt":   { "sha256": "…" },
    "CIS_COMPO_bdpm.txt": { "sha256": "…" },
    "CIS_MITM.txt":       { "sha256": "…" }
  },
  "counts": { "presentations": 20896, "withdrawn": 0, "byCategory": { "pain-fever": 0, "…": 0 } },
  "contentSha256": "…"
}
```

Keys are written sorted, two-space indented, UTF-8, trailing newline, so the file is
deterministic. **Canonical content** for `contentSha256`: UTF-8 bytes of
`meta\t<key>\t<value>\n` for each meta row sorted by key, then
`row\t<cip13>\t<name>\t<category>\t<withdrawnSince or empty>\n` for each presentation sorted by
CIP13, after `user_version\t<n>\n`. Names never contain tabs or newlines (the parser rejects
them, §4.2).

## 4. Build script (`scripts/build-reference-db.py`)

### 4.1 Commands

```
# routine data update (maintainer)
python3 -I scripts/build-reference-db.py download <empty-dir>
python3 -I scripts/build-reference-db.py build --bdpm <dir> --data-date YYYY-MM-DD \
        [--previous reference-data/bundle/reference.sqlite] [--out reference-data/bundle] [--accept-drop]
# gate
python3 -I scripts/build-reference-db.py verify [--bundle reference-data/bundle]
```

`--rules`, `--taxonomy` and `--golden` default to `reference-data/rules.json`,
`fixtures/categories.json` and `reference-data/golden-list.json`. `--previous` defaults to the
committed bundle when it exists. Exit code 0 on success, 1 on any validation failure, with one
message per problem on stderr.

- **download** (B12): refuses a non-empty directory; fetches the four files from
  `https://base-donnees-publique.medicaments.gouv.fr/download/file/<name>` with `urllib`
  (HTTPS only, the host is a constant, redirects to another host refused, 50 MB cap per file,
  60 s timeout); prints each file's SHA-256 and the reminder to read the publication dates on
  the download page. It never builds. BDPM publishes no checksums; integrity rests on HTTPS,
  the validation of §4.2, the 95 % guard and the reviewed build report.
- **build**: §4.2–§4.6, writing `reference.sqlite` and `reference-manifest.json` into a
  temporary directory next to `--out`, then moving both into place only when everything passed
  (B3: nothing written on failure).
- **verify** (B14, C6): opens the committed database read-only, checks `user_version`, the meta
  keys, `PRAGMA integrity_check`, recomputes `contentSha256` against the manifest, checks every
  category against the taxonomy (never `first-aid`), runs the golden list (§5), and checks that
  `ios/project.yml` and `android/app/build.gradle.kts` reference `reference-data/bundle`.

### 4.2 Reading BDPM files (B3, B4, B5, B6)

| File | Encoding | Columns | Used |
|------|----------|---------|------|
| `CIS_bdpm.txt` | cp1252 | 12 | CIS, name (2), form (3), routes (4, `;`-separated), procedure type (6) |
| `CIS_CIP_bdpm.txt` | UTF-8 | 13 | CIS (1), CIP13 (7) |
| `CIS_COMPO_bdpm.txt` | cp1252 | 8 | CIS (1), substance name (4), nature (7: `SA` only) |
| `CIS_MITM.txt` | cp1252 | 7 | CIS (1), ATC (2) |

- Decode strictly (`errors="strict"`); split lines on `\r\n` or `\n`; skip empty lines; split on
  `\t`; strip each field; wrong column count → error with file and line number.
- Validation: CIS 8 digits; CIP13 `3400` + 9 digits with a valid GS1 check digit; ATC
  `[A-Z][0-9]{2}[A-Z]{0,2}[0-9]{0,2}`; names 1–255 characters, no control characters; a
  duplicated CIP13 → error. Any failure stops the build.
- `--data-date` must be a real calendar day. (A cross-check against the declaration dates of
  `CIS_CIP_bdpm.txt` was dropped: the source contains typos such as the year 2924, so it would
  fail on real data. The date is checked against the previous database instead, B7.3.)
- Sample inputs are generated by `scripts/tests/reference_sample.py` (invented products, the real
  encodings) rather than committed as raw files, so malformed variants are derived in code.
- A presentation whose CIS is not in `CIS_bdpm.txt` is skipped and counted (B6; 4 today).

### 4.3 Merge (B7)

```
previous = rows of --previous (empty on the first build)
snapshot = {cip13: (name, category)} from the files
B7.3  fail if data-date < previous.meta.dataDate
B7.4  active_prev = CIP13 of previous rows with withdrawnSince NULL
      fail if |active_prev ∩ snapshot| < 0.95 × |active_prev|, unless --accept-drop
B7.1  for cip13 in snapshot: row = (name, category, withdrawnSince = NULL)
B7.2  for cip13 in previous − snapshot: keep (name, category),
      withdrawnSince = previous.withdrawnSince or data-date
```

The report (B9) lists counts for: added, withdrawn this build, reinstated, renamed,
recategorized, skipped, plus the totals by category before → after, and the first 20 CIP13 of
each change list so the commit can be reviewed.

### 4.4 Classification engine (C3, C4, C5)

Per CIS, the engine builds a **profile**: normalized routes, normalized form, the set of `SA`
substances (normalized), the ATC code if any, the procedure type. Normalization: Unicode NFKD,
combining marks removed, upper case, runs of spaces collapsed (so `PARACÉTAMOL` →
`PARACETAMOL`).

`reference-data/rules.json`:

```json
{
  "formatVersion": 1,
  "rulesVersion": 1,
  "description": "Ordered classification rules; the first rule whose conditions all hold gives the category.",
  "rules": [
    { "name": "homeopathy", "when": { "procedureAny": ["HOMEO"] }, "category": "other" },
    { "name": "eye", "when": { "routesAny": ["OPHTALMIQUE"] }, "category": "eyes" }
  ]
}
```

Conditions (all present conditions must hold; inside one condition, any listed value matches):

| Key | Holds when |
|-----|-----------|
| `routesAny` | one of the product's routes equals a listed value |
| `routesOnly` | every route of the product is in the list |
| `formAny` | the form contains a listed substring |
| `substancesAny` | a substance contains a listed token at the start of a word |
| `substancesOnly` | every substance contains one of the listed tokens |
| `atcAny` | the ATC code starts with a listed prefix |
| `procedureAny` | the procedure type contains a listed substring |

No match → `uncategorized`. The script fails when a rule is unnamed, names are duplicated, a
key is unknown, a list is empty, or the category is not in the taxonomy or is `first-aid` /
`uncategorized`. Classification is per CIS; every CIP13 of a CIS gets the same category.

**Initial rule order** (rules version 1, values abridged; the full lists are in the file and
are proven by the golden list):

1. `homeopathy` — procedure contains `HOMEO` → `other`
2. `parenteral-only` — routes only intravenous / intramuscular / subcutaneous / infusion-type → `other`
3. `eye` — route `OPHTALMIQUE` → `eyes`
4. `ear-nose` — route `AURICULAIRE` or `NASALE` → `cold-ent`
5. `gum-teeth` — route `GINGIVALE` or `DENTAIRE` → `mouth-teeth`
6. `throat-lozenge` — route `VOIE BUCCALE AUTRE` and form `PASTILLE` / `COMPRIME A SUCER` / `COLLUTOIRE` → `cold-ent`
7. `mouth` — route `VOIE BUCCALE AUTRE` → `mouth-teeth`
8. `long-term-or-specialist` — ATC `B01`, `C`, `G03`, `H`, `J01`, `J02`, `J04`, `J05`, `L`, `N03`, `N05`, `N06`, `A10` → `other`
9. `topical-muscle` — routes `CUTANEE`/`TRANSDERMIQUE` and substances `DICLOFENAC`, `KETOPROFENE`, `IBUPROFENE`, `PIROXICAM`, `NIFLUMIQUE`, `SALICYLATE DE METHYLE` → `muscles-joints`
10. `skin` — route `CUTANEE` → `skin-wound`
11. `cold` — substances `PSEUDOEPHEDRINE`, `CARBOCISTEINE`, `ACETYLCYSTEINE`, `AMBROXOL`, `OXOMEMAZINE`, `PHOLCODINE`, `DEXTROMETHORPHANE`, `HELICIDINE`, `GUAIFENESINE` → `cold-ent`
12. `pain` — substances `PARACETAMOL`, `IBUPROFENE`, `ACETYLSALICYL`, `KETOPROFENE`, `DICLOFENAC`, `NAPROXENE` → `pain-fever`
13. `muscle` — substances `THIOCOLCHICOSIDE`, `METHOCARBAMOL` → `muscles-joints`
14. `allergy` — substances `CETIRIZINE`, `LEVOCETIRIZINE`, `LORATADINE`, `DESLORATADINE`, `FEXOFENADINE`, `BILASTINE`, `EBASTINE`, `DEXCHLORPHENIRAMINE`, `MEQUITAZINE` → `allergy`
15. `digestion` — substances `DIOSMECTITE`, `LOPERAMIDE`, `RACECADOTRIL`, `ALGINATE`, `PHLOROGLUCINOL`, `MACROGOL`, `LACTULOSE`, `SIMETICONE`, `ALUMINIUM`, `OMEPRAZOLE`, `ESOMEPRAZOLE`, `PANTOPRAZOLE`, `TRIMEBUTINE`, `DOMPERIDONE` → `digestion`
16. `fluoride-only` — substances only `FLUORURE` → `mouth-teeth`
17. `vitamins-minerals` — substances `CHOLECALCIFEROL`, `ASCORBI`, `FERREUX`, `MAGNESIUM`, `FOLIQUE`, `CALCIUM`, `ZINC`, `VITAMINE` → `vitamins-minerals`
18. ATC fallbacks — `R05` → `cold-ent`, `R06` → `allergy`, `A02`/`A03`/`A06`/`A07` → `digestion`, `N02`/`M01` → `pain-fever`, `M02`/`M03` → `muscles-joints`, `D` → `skin-wound`, `S01` → `eyes`, `A11`/`A12`/`B03` → `vitamins-minerals`, `A01` → `mouth-teeth`

Order encodes the priorities the golden list checks: route-specific rules before substances
(antiallergic eye drops are `eyes`, not `allergy`), cough/decongestant substances before
painkillers and antihistamines (paracetamol + pseudoephedrine is `cold-ent`), topical NSAIDs
before skin, and ATC families for long-term or specialist treatments before painkillers
(antiplatelet aspirin is `other`). Known limit: a low-dose aspirin product without an ATC code
in `CIS_MITM` is classified `pain-fever`; the user can override it.

### 4.5 Taxonomy input

The script reads `fixtures/categories.json` (§6.1): the allowed categories and which ones the
build may assign (`assignedByBuild`).

### 4.6 Determinism (B8)

Inputs are sorted (CIS, CIP13, rules in file order); dictionaries are never iterated without
sorting; no timestamps are written; the manifest is serialized with sorted keys. A test builds
twice from the same inputs and compares manifests byte for byte and `contentSha256`.

## 5. Golden list (C6) — draft for product-owner review

`reference-data/golden-list.json` holds cases `{ name, cip13, expect }` (one CIP13 per product,
picked from the snapshot when the file is written). `build` refuses to write a database that
breaks a case; `verify` runs it against the committed database. Draft, from real products of
the 2026-10-19 snapshot (CIS in brackets):

| Expected | Products |
|----------|----------|
| `pain-fever` | DOLIPRANE 1000 mg comprimé [60234100] · EFFERALGAN 1000 mg effervescent [67058605] · ADVIL 200 mg [68634000] · ASPIRINE UPSA 500 mg [65265765] · VOLTARENE 50 mg comprimé (oral diclofenac) [62431527] · KETUM 100 mg comprimé [69159834] |
| `cold-ent` | HUMEX 5 % expectorant [67094067] · TOPLEXIL sirop (antihistamine for cough, not `allergy`) [62982231] · DOLIRHUME paracétamol + pseudoéphédrine (not `pain-fever`) [65089833] · ACTIFED RHUME [69928332] · STREPSILS ORANGE VITAMINE C (not `vitamins-minerals`, not `mouth-teeth`) [63530827] · OTIPAX ear drops [69668703] · RHINOFLUIMUCIL [62249357] · AVAMYS nasal [66921927] · ALLERGODIL nasal spray (nose form of an anti-allergy) [68576006] |
| `digestion` | SMECTALIA [65118886] · IMODIUMCAPS [61203061] · GAVISCON [63636264] · SPASFON suppositoire (rectal) [65923640] · MOVICOL [60885246] · FORLAX 10 g [69298346] |
| `allergy` | CETIRIZINE EG [60807297] · AERIUS 5 mg [61833327] · POLARAMINE 2 mg comprimé [65570749] |
| `skin-wound` | BIAFINE [69931588] · BETADINE DERMIQUE [68964585] · FUCIDINE pommade [62345035] · HEXOMEDINE gel [69884544] · ZOVIRAX 5 % crème [65069229] |
| `eyes` | DACRYOSERUM [69667569] · TOBREX collyre [67299307] · OPTICRON unidose [67385005] · ALLERGODIL collyre (eye form of an anti-allergy) [67399275] · CROMABAK [67898381] |
| `muscles-joints` | VOLTARENE EMULGEL (topical diclofenac) [63855240] · FLECTOREFFIGEL [61183406] · KETUM gel [63736058] · MIOREL gélule [65617572] · THIOCOLCHICOSIDE ARROW [64045168] |
| `mouth-teeth` | HEXTRIL [67376038] · ELUDRILPERIO [67960413] · PANSORAL (salicylate on gums, not `pain-fever`) [66011780] · DYNEXAN [68029058] · ZYMAFLUOR [61189302] |
| `vitamins-minerals` | UVEDOSE capsule [63158750] · ZYMADUO (vitamin D + fluoride) [67512328] · LAROSCORBINE [69435752] · TARDYFERON 50 mg [61879666] · MAG 2 100 mg [68633852] · SPECIAFOLDINE 0,4 mg [64732689] |
| `other` | KARDEGIC 160 mg (antiplatelet aspirin, not `pain-fever`) [61348441] · ZOVIRAX 200 mg comprimé (oral antiviral) [68670676] · AMOXICILLINE BIOGARAN 500 mg [69118175] · LEVOTHYROX 25 µg [68588503] · OPTIMIZETTE [67203730] · LANTUS [64745003] · POLARAMINE injectable [65227405] · ARNICA MONTANA BIOGARAN CONSEIL gel (homeopathy) [60706832] |

**Choices for the product owner to confirm with this list:** anti-allergy nose sprays go to
`cold-ent` and anti-allergy eye drops to `eyes` (where they are used, not why); oral painkillers
are `pain-fever` while the same substance as a gel is `muscles-joints`; antibiotics, antivirals
taken orally, hormones, insulin, heart and other long-term treatments are `other`; homeopathy,
including arnica gel, is `other`; vitamin D with fluoride is `vitamins-minerals`.

## 6. Shared fixtures

### 6.1 `fixtures/categories.json` (C1, C2, C4, C8, C9)

```json
{ "formatVersion": 1,
  "description": "The category taxonomy in display order. assignedByBuild: the reference-data build may give it; userSelectable: offered when the user picks a category.",
  "cases": [
    { "name": "pain-fever", "assignedByBuild": true, "userSelectable": true },
    "… the twelve categories in the order of requirements C1 …",
    { "name": "first-aid", "assignedByBuild": false, "userSelectable": true },
    { "name": "other", "assignedByBuild": true, "userSelectable": true },
    { "name": "uncategorized", "assignedByBuild": false, "userSelectable": false }
  ] }
```

`uncategorized` is the build's no-match result, not something a rule assigns
(`assignedByBuild: false`). Both cores assert their `Taxonomy` equals this list in order. Both
apps assert every case has a label in French and English, under the key `category.<id>` with
`-` written `_` (`category.pain_fever` on iOS, `category_pain_fever` on Android), so the existing
parity mapping (`.` → `_`) applies unchanged.

### 6.2 `fixtures/reference-lookup.json` (L2, L3, L5)

```json
{ "name": "DataMatrix GTIN of a known CIP13", "gtin": "0340093…",
  "expect": { "cip13": "3400930000001", "product": { "name": "…", "category": "pain-fever" } } }
```

`expect.cip13` is checked by both cores (pure conversion); `expect.product` (or `null` =
unknown) is checked by both apps against `fixtures/reference-sample.sqlite`. Cases: a known
active CIP13; a known **withdrawn** CIP13 (still found); an unknown `3400…`; an ACL `03401…`
(`cip13: null`); a foreign GTIN; a wrong check digit; 13 digits; 15 digits; letters; empty; a
string of SQL (`' OR 1=1 --` padded to 14 characters).

### 6.3 `fixtures/reference-sample.sqlite`

Built by `build` from `scripts/tests/reference-data/bdpm/` (≈ 10 products, both encodings,
an accented name with `œ`, a withdrawn product via a second, later snapshot of the same sample).
The script's tests rebuild it and compare `contentSha256` with the committed file, so it cannot
go stale. `fixtures/README.md` documents it next to the `.sql` files.

## 7. Core (both platforms)

New folder `Sources/MediCabinetCore/Reference/` (Swift) and package
`com.medicabinet.core.reference` (Kotlin). Same names, same behaviour.

```swift
// Taxonomy.swift
extension CategoryID {
    public static let painFever = CategoryID(rawValue: "pain-fever")
    // … one constant per category of C1; `uncategorized` already exists in Model.swift
}
public enum Taxonomy {
    public static let ordered: [CategoryID]              // the twelve, C1 order
    public static let userSelectable: [CategoryID]       // ordered minus uncategorized (C9)
    public static func displayed(_ id: CategoryID) -> CategoryID   // unknown id → .other (C8)
}

// ProductLookup.swift — exactly bulk-scan design §3.3
public struct ReferenceProduct: Hashable, Sendable { public let name: String; public let category: CategoryID? }
public protocol ProductLookup: Sendable { func product(gtin: String) async throws -> ReferenceProduct? }

// CIP13.swift
public enum CIP13 {
    /// 14 ASCII digits, prefix `03400`, valid GS1 check digit → the 13-digit CIP13; else nil (L2).
    public static func from(gtin: String) -> String?
}

// ReferenceData.swift
public struct ReferenceDataDate: Hashable, Sendable { public let year, month, day: Int
    public init?(iso: String) }                          // strict YYYY-MM-DD, real calendar day
public enum ReferenceUnavailableReason: String, Sendable { case missing, unreadable, unsupportedSchema }
public enum ReferenceStatus: Hashable, Sendable { case available(dataDate: ReferenceDataDate), unavailable(ReferenceUnavailableReason) }
public protocol ReferenceDataSource: ProductLookup { func status() async -> ReferenceStatus }
```

Kotlin: `object Taxonomy`, `CategoryID.PAIN_FEVER` …, `data class ReferenceProduct`,
`interface ProductLookup { suspend fun product(gtin: String): ReferenceProduct? }`,
`object Cip13 { fun fromGtin(gtin: String): String? }`, `ReferenceDataDate.parse(iso)`,
`enum class ReferenceUnavailableReason(val rawValue: String)`, `sealed interface ReferenceStatus`,
`interface ReferenceDataSource : ProductLookup { suspend fun status(): ReferenceStatus }`.
The check-digit helper is private to `CIP13` here; bulk-scan's `GTIN` validation can reuse it
after its rebase (noted in §15).

Test support (Swift `MediCabinetCoreTestSupport`, Kotlin `:core` test fixtures):
`InMemoryReferenceData` (a dictionary CIP13 → product, a settable status) and `SharedFixtures`
loaders for `categories.json` and `reference-lookup.json`.

## 8. iOS app

```
MediCabinet/Sources/Persistence/Reference/
  ReferenceDatabase.swift     # open read-only, validate, read meta
  GRDBReferenceData.swift     # actor GRDBReferenceData: ReferenceDataSource (lazy, memoized)
MediCabinet/Sources/Features/Settings/
  AboutViewModel.swift        # @Observable @MainActor; reference state
  SettingsView.swift          # About gains the "Reference data" section
MediCabinet/Sources/Features/CategoryLabel.swift   # CategoryID → LocalizedStringResource (C2, C8)
```

- `ReferenceDatabase.open(at url: URL?)`: `nil` URL → `.missing`. Opens a GRDB `DatabaseQueue`
  with `configuration.readonly = true` (and the `immutable` URI flag if GRDB's path API accepts
  it — checked in T9, otherwise plain read-only). Then: `PRAGMA user_version` must be `1` else
  `.unsupportedSchema`; `PRAGMA quick_check` must return `ok`, `meta.dataDate` must parse,
  else `.unreadable`. Any GRDB error → `.unreadable`.
- `GRDBReferenceData` (actor): the first call to `status()` or `product(gtin:)` opens the
  database once and keeps the result (open queue or failure reason). `product(gtin:)`:
  `CIP13.from(gtin:)` → `nil` returns unknown with no query; else
  `try await queue.read { try Row.fetchOne($0, sql: "SELECT name, category FROM presentation WHERE cip13 = ?", arguments: [cip13]) }`.
  When unavailable it returns `nil` (L7: every product unknown) and never throws for that
  reason. A failure is logged once: `Log.reference.error("reference data unavailable: \(reason.rawValue)")`
  — the reason only, never a GTIN or name.
- `AppEnvironment` gains `reference: any ReferenceDataSource`, built in `Bootstrap` with the
  bundle URL `Bundle.main.url(forResource: "reference", withExtension: "sqlite")` (no I/O at that
  point).
- About: `AboutViewModel` loads `status()` in `.task`; states `loading` → `available(date)` →
  shows "Data of <date>" with `Date.FormatStyle` (date only, user locale; the date is built in
  the Gregorian calendar at noon in the current time zone so no time-zone shift changes the day),
  or `unavailable`. The section shows the source name, the address as plain text (a `Link` to
  open it in the browser), the date line, and the not-endorsed statement (A1–A2).
- `project.yml`: the two bundle files are added as resources of the `MediCabinet` target
  (`- path: ../reference-data/bundle/reference.sqlite`, `buildPhase: resources`; same for the
  manifest). Data Protection is not applied: the file is part of the signed bundle, read-only,
  and holds no household data.

## 9. Android app

```
app/src/main/kotlin/com/medicabinet/app/persistence/reference/
  ReferenceAssets.kt          # interface: manifest JSON + open the database stream (assets in the app, files in tests)
  ReferenceInstaller.kt       # copy to noBackupFilesDir/reference when needed (L8)
  ReferenceDatabase.kt        # open read-only, validate, read meta
  SqlReferenceData.kt         # class SqlReferenceData : ReferenceDataSource (Mutex, injected IO dispatcher)
app/src/main/kotlin/com/medicabinet/app/features/CategoryLabels.kt   # CategoryID → @StringRes
```

- `ReferenceInstaller.install()`: reads `contentSha256` from `reference-manifest.json` (assets,
  `org.json`); if `noBackupFilesDir/reference/installed.sha256` holds the same value and
  `reference.sqlite` exists, nothing to do. Otherwise: stream the asset to
  `reference.sqlite.tmp` while computing SHA-256, `fsync`, re-read the written file's SHA-256,
  compare, rename over `reference.sqlite`, then write `installed.sha256` (written last, so an
  interrupted copy is redone on the next try). Any failure deletes the temporary file and maps
  to `unreadable` (`missing` when the asset is absent).
- `ReferenceDatabase.open(file)`: `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY or NO_LOCALIZED_COLLATORS)`;
  same checks as iOS (`user_version`, `quick_check`, `meta.dataDate`). A `SQLiteException` maps
  to `unreadable`. The cabinet's `onCorruption` override is not involved: this database is
  separate and re-copied from the APK.
- `SqlReferenceData(installer, ioDispatcher)`: `Mutex`-guarded lazy open on the IO dispatcher;
  `product(gtin)` uses `Cip13.fromGtin` then `rawQuery("SELECT name, category FROM presentation WHERE cip13 = ?", arrayOf(cip13))`.
  Failure logged once via the new `AppLog.referenceUnavailable(reason: ReferenceUnavailableReason, error: Throwable?)`
  (reason raw value and exception class only).
- `AppEnvironment` gains `reference: ReferenceDataSource`, created by `MediCabinetApplication`
  without I/O.
- About: `AboutViewModel` exposes `StateFlow<ReferenceUiState>` loaded in `viewModelScope`;
  the date is formatted with `DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)` and the
  current locale. Same section content as iOS.
- `app/build.gradle.kts`: `android.sourceSets["main"].assets` gains the directory
  `../../reference-data/bundle` (exact AGP 9 DSL settled in T12). The asset stays compressed in
  the APK (it is copied anyway).
- All of this sits under `persistence/`, so `sql_confined_to_persistence` keeps holding.

## 10. Gate (local CI)

`scripts/local-ci.sh` shared section gains a step **"shared: reference data"**:

1. `python3 -I scripts/tests/test-build-reference-db.py` — the script's unit tests (B3–B13,
   C3–C6 on sample inputs).
2. `python3 -I scripts/build-reference-db.py verify` — the committed bundle (B14, C4, C6).
3. `bash scripts/tests/test-reference-data-break.sh` — a copy of the bundle with one golden
   expectation flipped, and one with a row edited, must both make `verify` fail.

`fixtures/categories.json` and `reference-lookup.json` are covered by `check-fixtures.py` and by
`test-shared-fixtures-break.sh` (a wrong case fails each platform's suite).

## 11. Flows

**Data update (maintainer, documented in `docs/reference-data.md`):** `download` into an empty
directory → read the publication dates on the BDPM page → `build --data-date <latest date>` →
read the report (drops, renames, recategorizations) → `verify` → commit the bundle with the
report in the commit body → bump both apps (data updates are user-visible: About shows the new
date) → release.

**Lookup (bulk-scan, after T29 binds it):** scanner gives GTIN → `reference.product(gtin)` →
first call opens the database (Android: installs the copy first) → `CIP13.from` → one indexed
read → `ReferenceProduct` → `ProductResolver.resolve`.

**About:** open About → `loading` → `status()` (opens the database if needed) → date or
"unavailable".

## 12. Performance

| What | Target | Measured / budget |
|------|--------|-------------------|
| Lookup | < 50 ms (L6) | 15 µs per read on a desktop. Test: 500 lookups over the full database on the simulator/Robolectric after open; every lookup < 50 ms, median < 5 ms |
| First open | off main, not at launch | `quick_check` on 1.8 MB plus open; budget < 500 ms, asserted |
| Android first copy | off main | 1.8 MB stream copy plus two hashes; budget < 1 s, asserted on Robolectric |
| App size | < 50 MB | +0.4 MB download, +1.8 MB installed (§3.2); exact deltas measured in T17 and stated in the PR |
| Cold start | unchanged | nothing added to bootstrap (§1.6); the existing launch measurement is rerun |

No main-thread I/O: GRDB async reads on iOS, IO dispatcher on Android.

## 13. Privacy impact

- No new permission; Android keeps no `INTERNET` permission; the About link opens the system
  browser through an `ACTION_VIEW` intent / SwiftUI `Link`, which needs none.
- The reference database contains only public BDPM data. Nothing about the household is written
  to it or derived from it.
- Logs: only the unavailable reason (and exception class on Android). Never a GTIN, CIP13 or
  product name (X1). The existing privacy/logging tests are extended to the new code.
- `PrivacyInfo.xcprivacy`: no new required-reason API (no file timestamps, no `UserDefaults`).
- Android backup: `noBackupFilesDir` is excluded by the platform; the backup rules are unchanged
  and a test asserts they do not include `reference`.

## 14. Test strategy

| Requirement | Test | Where |
|-------------|------|-------|
| S1–S2 | `docs/reference-data.md` (done, b2c23b1); format drift fails B3 tests | docs, script |
| B1, B2, B10 | build on sample inputs → schema, columns, meta, manifest keys | script tests |
| B3, B4 | one test per malformed input: missing file, bad encoding, wrong column count, bad CIP13, bad check digit, duplicate CIP13, 256-char name, control character; output dir untouched | script tests |
| B5, B6 | names unaltered (accents, `œ`); orphan presentation skipped and counted | script tests |
| B7 | merge: add, withdraw (date kept on later builds), reinstate, rename, older data date fails, 95 % guard and `--accept-drop` | script tests |
| B8 | build twice → identical manifest and `contentSha256` | script tests |
| B9 | report lists each change kind | script tests |
| B11 | budget exceeded (lowered for the test) fails | script tests |
| B12 | `download` refuses non-empty dir, non-HTTPS, foreign host (URL opener stubbed) | script tests |
| B13, B14 | gate step; break script | `local-ci.sh`, `test-reference-data-break.sh` |
| C1, C2, C9 | `TaxonomyFixtureTests` / `TaxonomyFixtureTest` vs `categories.json`; label tests in both apps | core, app |
| C3–C5 | rules-engine tests per condition key and ordering; invalid rules files fail | script tests |
| C6 | golden list at build and in `verify` | script, gate |
| C7 | label wording reviewed here (requirements C1); About statement test | review, app |
| C8 | `Taxonomy.displayed` unknown → other, value unchanged | core |
| C10 | already covered by bulk-scan's `ProductResolver` fixture | bulk-scan |
| L1 | compiles with bulk-scan's call sites after rebase (T29) | bulk-scan |
| L2 | `CIP13FixtureTests` / `Cip13FixtureTest` on `reference-lookup.json` | core |
| L3, L4, L5 | `ReferenceLookupFixtureTests` (iOS, GRDB) / `ReferenceLookupFixtureTest` (Robolectric) on `reference-sample.sqlite` | app |
| L6 | performance tests on the bundled database | app |
| L7 | missing file, corrupt file (truncated copy), `user_version = 2`, bad `dataDate` → unavailable, lookup returns unknown, no throw, one log line | app |
| L8 | installer: first install, unchanged hash skips, changed hash replaces, interrupted copy (fault-injected stream) leaves no file and retries, hash mismatch fails | Android app |
| L9 | integration test opens the real bundled database and finds DOLIPRANE 1000 mg comprimé | app |
| A1–A3 | About UI tests (iOS XCUITest, Robolectric Compose) for available and unavailable; string checks | app |
| X1 | logging tests (no GTIN/name), Android `PrivacyTest` (no INTERNET) | app |
| X2 | backup-rules test; iOS never writes (opened read-only) | app |
| X3, X8 | size measurement (T17); launch measurement rerun | PR |

## 15. i18n impact

New keys (iOS `Localizable.xcstrings` key / Android name with `.` → `_`), French / English:

| Key | French | English |
|-----|--------|---------|
| `category.pain_fever` … `category.uncategorized` | the twelve labels of requirements C1 | same |
| `about.reference.title` | Données de référence | Reference data |
| `about.reference.source` | Source : Base de Données Publique des Médicaments | Source: Base de Données Publique des Médicaments |
| `about.reference.date` | Données du %1$@ | Data as of %1$@ |
| `about.reference.loading` | Chargement des données de référence… | Loading reference data… |
| `about.reference.unavailable` | Données de référence indisponibles : les produits scannés devront être nommés à la main. | Reference data unavailable: scanned products will need to be named by hand. |
| `about.reference.notEndorsed` | MediCabinet n'est ni affilié à l'ANSM, à la HAS ou à l'Assurance Maladie, ni approuvé par eux. Les catégories sont proposées par MediCabinet pour ranger l'armoire. | MediCabinet is not affiliated with or endorsed by ANSM, HAS or the French national health insurance. Categories are suggested by MediCabinet to tidy the cabinet. |

The address `base-donnees-publique.medicaments.gouv.fr` and the name "Base de Données Publique
des Médicaments" are proper nouns, not translated. Category keys write the ID's `-` as `_`
(`category.pain_fever` / `category_pain_fever`), so `check-string-parity.py` needs no change.

## 16. Docs and CLAUDE.md

- `docs/reference-data.md`: data-update procedure (§11), rules-file format (§4.4), reference
  schema and manifest (§3), golden list.
- `docs/architecture.md`: the reference database next to the cabinet database (read-only,
  bundled, lazy; Android copy).
- `fixtures/README.md`: `categories.json`, `reference-lookup.json`, `reference-sample.sqlite`;
  names that cross platforms: the twelve category IDs, `ReferenceUnavailableReason`
  (`missing`, `unreadable`, `unsupportedSchema`).
- `CLAUDE.md`: Architecture — the cores hold the **taxonomy**, GTIN → CIP13 and override
  resolution; **classification is computed by the reference-data build**
  (`scripts/build-reference-db.py`, `reference-data/rules.json`). Project structure gains
  `reference-data/`. Common Task Map gains "Update the BDPM reference data" and "Change a
  classification rule".

## 17. Version

iOS **0.1.1** (build 2) and Android **0.1.1** (versionCode 2): the user-visible change is the
reference-data section in About (PATCH). Changelog entries on both. Bulk-scan, rebased after
this epic, then ships 0.2.0 with build/versionCode **3**.

## 18. Risks

- **Classification quality outside the golden list.** Rules are checked on ~60 products; the
  remaining ~15 000 rely on them generalizing. The build report's per-category totals and the
  `uncategorized` count make drift visible; the user can always override.
- **Bulk-scan rebase.** Both branches touch `AppEnvironment` and the core `ProductLookup`
  declarations; the conflict is small and expected (§1.7). Bulk-scan's build numbers move to 3.
- **Data freshness.** The licence makes the reuser responsible for stale data; About shows the
  data date and updates ride on releases. An in-app refresh is out of scope.
- **BDPM format change** (new column, encoding switch): the build fails loudly (B3) rather than
  shipping wrong data; the spike page is updated first (S2).
- **GRDB read-only open of a bundle file**: if the `immutable` flag is unavailable, plain
  read-only works because the file is in `DELETE` journal mode; verified in T9.
