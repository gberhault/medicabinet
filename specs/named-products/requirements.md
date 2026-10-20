# Products you named — Requirements

Status: **Pre-approved 2026-10-20** (the user approved requirements, design and tasks in advance; implementation
waits for the user's go)
Issue: #57 · Branch: `feature/57-named-products` · Platforms: iOS + Android

## Introduction

When a scanned code is not in the bundled medicines database (parapharmacy, supplements, devices, foreign
products), the household types a name once, and the app reuses it on every later scan of that code. Today that
memory is not stored anywhere of its own: the app reads the name of the **newest box** with the same code, including
boxes already used up or thrown away. So a wrong name (a typo, a test name) keeps coming back. It cannot be fixed
once that box has left the cabinet, because box detail is only reachable for boxes in the cabinet. The household has
no place to see which names it gave, fix one, or make the app forget one.

This spec gives that memory its own place: one **named product** per code, listed in **Settings → Products you
named**. From there the household can rename a product, which renames all its boxes, change its category, or forget
it, so the next scan asks again.

> We are adding, on iOS and Android, a stored **named product** per code that the medicines database does not name
> (the name and the category the household gave it), filled from the boxes already in the database. It replaces the
> "newest box" fallback for those codes and is listed in **Settings → Products you named**, where the household can
> **rename** a product (every box with that code, in the cabinet and in the history, takes the new name), change its
> **category** (every box with that code), or **forget** it (the boxes keep their name; the next scan asks for one;
> confirmation with the number of boxes still in the cabinet, then Undo). The export carries the named products in a
> new archive version, and older files still import. We are **not** renaming medicines the database names,
> remembering anything for boxes with no code, merging two products, or adding sync.

### Decisions taken in the challenge (2026-10-20)

1. **Rename renames every box with the code**, in the cabinet and in the used-up / thrown-away history. The name is a
   property of the product, so a fix applies everywhere (user's choice).
2. **Forget removes the memory and touches no box.** The confirmation says how many boxes are still in the cabinet
   and that they keep their name. Undo is offered (user's choice).
3. **A table of its own**, not a value derived from boxes. Only a stored row can be forgotten while its boxes remain,
   and survive the last box leaving the cabinet. It is sync-ready (UUID, `updatedAt`, tombstone).
4. **Only codes the database does not name.** For a code the database knows, its name always wins, as today. The
   carry-over of a category the user set on such a code stays as it is (newest box). Both are out of scope here.
5. **The last name typed wins.** Whenever the household types or changes the name or the category of a box whose code
   the database does not name, that becomes the remembered name and category. This is how the newest-box rule behaves
   today in the common case. It applies to a new box (scan, *Add without scanning*), a correction in the scan
   session list and a box edit. It does **not** rename the product's other boxes: only the Settings rename does.
6. **Category changes in Settings apply to every box with the code**, like rename. *This was not asked explicitly:
   it is the consistent reading of decision 1, and is listed for the user's review.*
7. **Both platforms**, same behaviour and wording.

### Requirements this spec supersedes

- `specs/bulk-scan/` — the product name for a code the reference does not know came from "the newest box with the
  same code". It now comes from the named product (N3). The rule for codes the reference knows is unchanged.
- `specs/reference-data/requirements.md` *Out of scope* — "A per-product override table". This spec adds one, for
  codes the reference does not name only.

## Glossary

- **Named product**: what the household taught the app about one code (GTIN) that the bundled database does not
  name: a name, a category, and whether the category was set by the user. At most one live named product per code.
- **Known code**: a GTIN for which the bundled reference database returns a product.
- **Reference available**: the reference database opened and answered (`ReferenceStatus.available`). When it is
  unavailable, every lookup answers "unknown", so the app cannot tell a known code from an unknown one.
- **Forget**: mark a named product deleted (tombstone `deletedAt`). Its boxes are untouched.

## User stories and acceptance criteria

Requirement IDs: **N** the named-product memory, **B** filling it from existing boxes, **S** the Settings list,
**E** rename and category, **F** forget, **A** export and import, **X** cross-cutting.

### N — The memory

*As a household member, I want the name I typed for an unknown product to be kept on its own, so that every later
scan uses it until I change it.*

1. The system SHALL store at most one live named product per GTIN: id (UUID), GTIN (14 digits), name (1–200
   characters once trimmed), category, *set by the user* flag, created, updated and deleted dates.
2. WHEN a box with a code is **added** (a scan completed by the user, *confirm another*, *Add without scanning* with a
   code), the reference is available and does not know the code, the system SHALL remember the box's name and
   category as that code's named product. It SHALL create the named product, or update it when its name or category
   differs. Nothing SHALL be written when they are equal.
3. WHEN a scan or a manual entry resolves a product name, the system SHALL use the reference name for a known code.
   Otherwise it SHALL use the named product's name, and otherwise it SHALL ask for a name. The category SHALL be the
   named product's when its *set by the user* flag is on, else *Uncategorized*. The newest box SHALL no longer
   provide a name or a category for a code the reference does not know.
4. WHEN the user **changes the name or the category** of a box with a code the reference does not know (box edit,
   *Automatic category*, or a correction in the scan session list), the system SHALL remember the box's new name and
   category for that code, creating the named product again if it had been forgotten. Other boxes SHALL not change.
5. WHEN a box is saved without a change to its name or category (expiry, location, *Opened*, take out, put back,
   undo), the system SHALL not create, update or bring back a named product.
6. WHILE the reference is **unavailable**, or WHEN the lookup fails, the system SHALL not create or update any named
   product. A scan SHALL still resolve from the named product (N3), so intake never stops.
7. A box without a code SHALL never create a named product.
8. The rules N2–N7 SHALL live in both cores and pass a shared fixture on both platforms.

### B — Filling the memory from existing boxes

*As a household member upgrading the app, I want the names I already gave to be kept, so that nothing I typed is
lost and I can fix the wrong ones in the new list.*

1. WHEN the cabinet database is upgraded to the version that adds named products, the system SHALL create one named
   product for every GTIN that has at least one box that is not deleted (in the cabinet or taken out) and that the
   reference does not know. The name, category and flag SHALL be taken from that GTIN's newest such box (created
   date, then id: the box the app reuses today).
2. The fill SHALL run once, after the upgrade, off the main thread and off the cold-start path. WHILE the reference is
   unavailable it SHALL not run, and it SHALL run at a later launch. An interrupted fill SHALL leave nothing half
   written and SHALL run again.
3. WHEN an archive of a format version without named products is imported, the system SHALL fill the memory from the
   imported boxes in the same way.
4. The fill rule SHALL live in both cores and pass a shared fixture on both platforms.

### S — The list in Settings

*As a household member, I want to see every product I named, so that I can spot a wrong or a test name.*

1. Settings SHALL show a **Products you named** row after *Locations*, opening the list.
2. The list SHALL show every live named product, ordered by name compared as normalized text, then by GTIN. Each row
   SHALL show the name, the code (the 13-digit form when the GTIN starts with `0`, as box detail shows it), the
   category label, and how many of its boxes are in the cabinet (*2 boxes in the cabinet*, *1 box in the cabinet*,
   *Not in the cabinet*).
3. WHEN there is no named product, the list SHALL show an empty state that explains when products appear here.
4. The list SHALL have loading and error states (with *Retry*), and SHALL follow changes made elsewhere (a scan, a box
   edit, an import) without leaving the screen.
5. Each row SHALL offer **Edit** and **Forget**: tap or swipe actions and a context menu on iOS, an overflow menu on
   Android. Both SHALL be reachable with VoiceOver / TalkBack as named actions.
6. Rows SHALL be at least 44 × 44 pt / 48 × 48 dp and follow Dynamic Type / font scale 200 % without clipping. The
   box count SHALL be text, not only an icon or colour.
7. The list SHALL be read with one query (no query per row). With 1 000 named products it SHALL scroll without
   dropped frames.

### E — Rename and category

*As a household member, I want to fix a product's name once for all its boxes, so that the cabinet, the history and
the next scan all show the right name.*

1. **Edit** SHALL open a form with the name (pre-filled) and the category (pre-selected; the user-selectable
   categories plus *Automatic category*, which here means *Uncategorized*, since the database does not know the
   code). It SHALL have **Save** and **Cancel**. Cancel SHALL change nothing.
2. The name SHALL be trimmed and SHALL be 1–200 characters. An empty or too long name SHALL be refused inline with
   text and an icon, and nothing SHALL be saved.
3. WHEN the user saves a new name, the system SHALL, in one transaction, set the named product's name and the name of
   **every box with that GTIN that is not deleted** (in the cabinet, used up or thrown away), and stamp their
   updated dates. A box tombstoned as *added by mistake* SHALL keep its name.
4. WHEN the user saves a new category, the system SHALL, in the same transaction, set the named product's category
   and flag and the category and flag of every box with that GTIN that is not deleted. *Automatic category* sets
   *Uncategorized* with the flag off.
5. The form SHALL say that the change applies to all the product's boxes, including those used up or thrown away.
6. WHEN the write fails, nothing SHALL change and the screen SHALL say so.

### F — Forget

*As a household member, I want the app to forget a name, so that the next scan asks me again.*

1. **Forget** SHALL ask for confirmation, with the product's name. WHEN boxes of it are in the cabinet, the message
   SHALL say how many and that they keep their name. The message SHALL say that the next scan of this code will ask
   for a name.
2. WHEN confirmed, the system SHALL mark the named product deleted and SHALL not change any box. The row SHALL leave
   the list.
3. The system SHALL then offer **Undo** for the time the platform's undo message lasts. Undo SHALL bring the named
   product back as it was. WHEN another named product for the same code was created in between, Undo SHALL say it
   cannot restore it and change nothing.
4. WHEN a forgotten code is scanned again, the system SHALL ask for a name (N3). The name typed SHALL create a new
   named product (N2).
5. Cancelling the confirmation SHALL change nothing.

### A — Export and import

*As a household member, I want my named products to move with the cabinet, so that a new phone keeps them.*

1. The export archive SHALL carry every named product, deleted ones included (with their deleted date), in a new
   `formatVersion` **2**. Exports SHALL be written in version 2.
2. The app SHALL keep importing version 1 files. Their named products SHALL be filled from their boxes (B3). Version 1's
   golden file and cases SHALL stay unchanged.
3. Importing a version 2 file SHALL validate the named products like the rest of the file (ids unique, a 14-digit
   GTIN, a name of 1–200 characters, at most one live named product per GTIN, a count limit). A file breaking a rule
   SHALL be refused whole, with the cabinet untouched.
4. Import SHALL replace the named products together with the boxes and locations, in the same transaction. The
   automatic backup taken before an import SHALL carry them.
5. A version 2 file opened by an app that only reads version 1 is refused as *newer version* (existing behaviour).

### X — Cross-cutting

1. **Schema.** The cabinet database SHALL move to schema **v4** through one versioned migration with identical SQL
   on both platforms (`fixtures/schema-v4.sql` / `.json`). A pre-migration backup SHALL be taken as for every
   migration.
2. **Privacy.** Named products are health data: they stay on the device, leave it only in an export, and SHALL never be
   logged (names, GTINs). No new permission.
3. **No medical content.** Categories stay for tidying, as stated in the category form's note.
4. **i18n.** Every new string in French and English on both platforms with identical wording; plurals for the box
   counts.
5. **Docs.** A new `docs/named-products.md` (linked from `docs/README.md`); `docs/export-format.md` (version 2);
   `docs/architecture.md` (table, migration); `docs/bulk-scan.md` (name resolution); `fixtures/README.md`.
6. **Versions.** Each app SHALL get a **minor** bump with a changelog entry.
7. **Tests.** Success, failure and edge cases on both platforms (design §11). Core coverage targets 100 % of
   branches; new app code 90 %.

## Out of scope

- Renaming or recategorizing medicines the database names, or listing them here.
- Changing how a category the user set on a **known** code carries over to the next scan.
- Boxes with no code (nothing to remember them by).
- Merging two named products, or giving one product several codes.
- Showing in the list that a later data update now names a code. The database name wins on the next scan anyway.
- Searching or filtering the list.
- Box detail for boxes taken out of the cabinet.
- Sync.
