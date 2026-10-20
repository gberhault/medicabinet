# Official leaflet link — Requirements

Status: **Pre-approved 2026-10-20** (the user approved requirements, design and tasks in advance; implementation
waits for the user's go)
Issue: #56 · Branch: `feature/56-leaflet-link` · Platforms: iOS + Android

## Introduction

A household member holding a box sometimes wants the official information that comes with it: the patient leaflet
(*notice*) is often thrown away with the box's paper insert. The French public medicines database (BDPM), which
MediCabinet already bundles for names and categories, publishes one public page per medicine. That page holds the
official leaflet, the summary of product characteristics and the official fact sheet. This spec adds a link from a
box's detail to that page, opened in the phone's browser. MediCabinet itself keeps giving no medical content: it only
points to the official text, as the About screen already points to the database's home page.

> We are adding, on iOS and Android, an **official page and leaflet** link to box detail for boxes whose code the
> bundled BDPM database knows, opening the medicine's public BDPM page in the system browser, so that the household
> can read the official leaflet of a medicine it owns. To build that link, the bundled reference database keeps each
> presentation's BDPM **CIS** code. We are **not** linking to Vidal or any commercial source, not showing leaflet text
> in the app, not adding a web view or a network permission, and not changing the cabinet database or the export
> format.

### Decisions taken in the challenge (2026-10-20)

1. **BDPM, not Vidal.** Vidal is a commercial service: its pages have no stable public address built from the drug
   code, and its terms restrict reuse. BDPM is the official public source, under the Licence Ouverte already
   recorded in `docs/reference-data.md`, and it is the source the app already bundles.
2. **The page, not a leaflet file.** The BDPM page for a medicine is
   `https://base-donnees-publique.medicaments.gouv.fr/medicament/<CIS>/extrait` (checked on 2026-10-20 with
   DOLIPRANE 1000 mg, comprimé, CIS 60234100). The leaflet is a tab of that page; there is no stable URL that opens
   the leaflet tab directly (`…/notice` returns 404). The old address `extrait.php?specid=<CIS>` redirects to it.
   The link's wording therefore says *official page and leaflet*, not *leaflet* alone.
3. **The system browser, not an in-app web view.** The browser loads the page, so the app needs no network
   permission and no ATS / network-security exception. This is the same mechanism as the About link.
4. **Medicines the database knows only.** Parapharmacy (`3401…`), supplements, devices, boxes added without a code
   and codes the database does not know get no link. A medicine withdrawn from the database gets no link either:
   its page may be gone.

## Glossary

- **CIS** (*Code Identifiant de Spécialité*): BDPM's 8-digit identifier of a medicine (a *spécialité*). One CIS has
  one or more presentations (CIP13 codes). It is the first column of `CIS_CIP_bdpm.txt`.
- **Reference product**: what the bundled reference database knows about a CIP13 (`ReferenceProduct`).
- **Leaflet link**: the BDPM page address built from a CIS (L2).

## User stories and acceptance criteria

Requirement IDs: **R** reference data, **L** leaflet link, **X** cross-cutting.

### R — The reference database keeps the CIS

*As the maintainer, I want the bundled reference database to keep each presentation's CIS, so that the apps can
point to the medicine's official page without any network lookup.*

1. The reference database SHALL store, for every presentation that is not withdrawn, its CIS: exactly 8 ASCII
   digits, rejected by the schema otherwise. A withdrawn presentation MAY have no CIS (R3). This amends
   `specs/reference-data/requirements.md` **B2**, which listed only CIP13, name, category and withdrawn state.
2. The reference-schema version SHALL become **2** (`PRAGMA user_version` and the manifest's
   `referenceSchemaVersion`). Both apps SHALL accept version 2 and treat any other version as
   `unsupportedSchema`, as today (the database ships inside the app, so both move together).
3. WHEN the build script builds against a previous database of reference-schema version 1, it SHALL accept it as
   the previous database. A presentation listed in the current snapshot SHALL take its CIS from the snapshot. A
   presentation carried over as withdrawn SHALL keep the CIS of the previous database, which is none when that
   database is version 1. A withdrawn presentation never gets a link (L3), so a missing CIS loses nothing.
4. The content checksum (`contentSha256`) SHALL cover the CIS. The Python build, `verify` and the Android installer's
   checksum SHALL compute the same value.
5. The database SHALL stay under its 8 MB budget and the measured size increase SHALL be stated in the PR (estimate:
   about +0.2 MB).
6. A reference lookup SHALL return the CIS with the name and category, and whether the presentation is withdrawn.
   The shared lookup fixture (`fixtures/reference-lookup.json`) SHALL say which CIS each found case returns.

### L — The link on box detail

*As a household member, I want to open the official page of a medicine I own, so that I can read its leaflet when
the paper one is lost.*

1. WHEN box detail shows a box whose code the bundled database knows and that presentation is not withdrawn, the
   system SHALL show a link labelled **Official page and leaflet** (*Fiche officielle et notice*) after the box's
   information and before the *take out* actions.
2. The link SHALL open `https://base-donnees-publique.medicaments.gouv.fr/medicament/<CIS>/extrait` in the system
   browser. The address SHALL be built in both cores by one rule covered by a shared fixture, from a CIS of exactly
   8 ASCII digits only. Any other value SHALL give no link. Only `https` SHALL be used.
3. WHEN the box has no code, its code is not a CIP13, the database does not know it, the presentation is withdrawn,
   or the reference data is unavailable, the system SHALL show no link and no placeholder.
4. WHEN the lookup has not answered yet, box detail SHALL show the rest of the screen at once. The link SHALL appear
   when the lookup answers, with no spinner and without moving the controls the user may be about to tap. The link
   sits below the information rows.
5. WHEN the system browser cannot be opened, the app SHALL not crash and SHALL say *The page could not be opened*.
6. Under the link, a short note SHALL say that the page comes from the public medicines database and opens in the
   browser (*Page de la base de données publique des médicaments, ouverte dans le navigateur*).
7. The link SHALL be a single control with a touch target of at least 44 × 44 pt (iOS) / 48 × 48 dp (Android). It
   SHALL be read by VoiceOver / TalkBack as a link (iOS) or button (Android) with its label and the hint *Opens in
   the browser*, carry an external-link icon that is decorative, and follow Dynamic Type / font scale 200 % without
   clipping.
8. The link SHALL be shown for a box in the cabinet. Box detail of a box taken out is not reachable today, and this
   spec does not add it.

### X — Cross-cutting

1. **No medical content in the app.** The app SHALL show no text taken from the leaflet. The link label and the note
   SHALL make no medical claim. The About screen's *not endorsed* statement stays.
2. **Privacy.** No new permission (Android keeps no `INTERNET` permission, the architecture and privacy tests still
   pass), no network code in the app, nothing sent by the app. The browser request reveals only which medicine page
   is opened, and only when the user taps. The CIS, GTIN, CIP13 and product names SHALL never be logged.
3. **No cabinet change.** No cabinet schema change, no export-format change: the CIS is looked up at view time from
   the box's code and never stored on the box.
4. **i18n.** Every new string SHALL exist in French and English on both platforms with identical wording (string
   checks and `check-string-parity.py` pass).
5. **Docs.** `docs/reference-data.md` (schema v2, checksum, CIS), `docs/cabinet.md` (box detail link) and
   `fixtures/README.md` SHALL be updated in the same commits as the change they describe.
6. **Versions.** Each app SHALL get a **minor** bump with a changelog entry. The bundled data date changes with the
   rebuild and the About screen shows it.
7. **Tests.** Success, failure and edge cases on both platforms (see design §9). Core coverage targets 100 % of
   branches; new app code 90 %.

## Out of scope

- Vidal, the ANSM site, or any other source.
- Showing leaflet text, the summary of product characteristics, dosage, indications or safety information in the
  app.
- An in-app browser (`SFSafariViewController`, Custom Tabs) or any download.
- A link for products the database does not know, or for withdrawn presentations.
- A link from the cabinet grid, the product-boxes screen, the scan cards or the Expiring tab.
- Storing the CIS on boxes or in the export.
- Opening the leaflet tab directly (BDPM offers no stable address for it).
