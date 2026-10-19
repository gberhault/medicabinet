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
