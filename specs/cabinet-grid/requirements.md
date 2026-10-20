# Cabinet grid: category cards, medicine pills and a filter menu — Requirements

Status: **Approved 2026-10-20**
Issue: #48 · Branch: `feature/48-cabinet-grid` · Platforms: iOS + Android

## Introduction

The Cabinet tab (epic #17) shows one tall row per product: the name, the box count, the earliest expiry and a status
line. Above the list, the box count, the location and status menus and the active-filter text sit under the search
field. On a phone this leaves room for about two medicines, so the household cannot see what it has at a glance,
which is the tab's whole purpose. This spec replaces the list with **full-width category cards**. Each card holds its
medicines as **pills** (short name and box count), and pills flow across the width of the card. Search and the two
filters move into **one filter menu** on the right of the search field. The list no longer shows dates or *OK*. A pill
is marked only when there is something to act on (expired, expiring soon, or an unknown date), because the cabinet
is where someone grabs a medicine and must never be told an unknown or expired box is fine.

> We are rebuilding the Cabinet list on iOS and Android as full-width category cards (category icon, label, total
> boxes; categories A–Z with *Other* and *Uncategorized* last), each holding pills with a short name and a box count
> in A–Z order, marked with an icon and tint only when expired, expiring soon or of unknown expiry, and a filter menu
> beside the search field, so that the household sees the whole cabinet at a glance. We are **not** changing the
> search and filter rules, the database, the export format, the product-boxes screen, box detail or the Expiring tab.

### Decisions taken in the challenge (2026-10-20)

Three prototypes were shown: A (two columns, no status), B (two columns, status only on alerts) and C (full-width
cards, status only on alerts). The user chose:

1. **Layout C**: one column of full-width category cards. Pills flow across the card's width and wrap to new lines.
   This is simpler to read than a two-column masonry, gives long names more room and needs no fallback at large text
   sizes.
2. **No dates and no *OK* in the list.** Pills of medicines that are expired, expiring soon or of unknown expiry carry
   an icon and a tint. Showing no status at all (option A) was rejected because it conflicts with the product rule
   that an unknown or expired box must never look fine.
3. **Icons kept**: a category icon on every card and a status icon on alert pills.
4. **Categories in A–Z order** of their label in the app's language, then *Other* (*Autres*), then *Uncategorized*
   (*Non classé*), always last.
5. **Pills in A–Z order** of their displayed name. Urgent medicines are not moved to the front of the card.
6. **Short names**: the stored name up to the first `", "` ("DOLIPRANE 1000 mg, comprimé" → "DOLIPRANE 1000 mg"). On
   the bundled BDPM data (20 896 names) a decimal comma such as "0,05 %" is never cut. The median short name is 24
   characters and the 90th percentile 41.
7. **Both platforms**, same behaviour and wording.

### Requirements this spec supersedes

From `specs/cabinet-inventory/requirements.md`: **C1** (category order), **C3** (row content), **C4** (row order),
**C7** (header placement) and the display part of **S5** (how active filters are shown). Every other C, S and P
requirement stays as written. That covers grouping into product rows, live updates, the states, the search rules,
the filter rules, the "no results" state and the fact that search and filters are never stored.

## Glossary

- **Product row**: unchanged from `cabinet-inventory`. All boxes in the cabinet that have the same product key (GTIN,
  else normalized name) and the same category, after filters. The row's **name** is the name of its most recently added
  box, and its **row status** is the most urgent status among its boxes.
- **Category card**: the block that shows one category and its product rows.
- **Pill**: how one product row is drawn inside a category card.
- **Short name**: the row name cut before its first `", "` (comma followed by a space) and trimmed. When the name has
  no `", "`, or when the cut leaves nothing, the short name is the whole trimmed name.
- **Displayed name**: what a pill shows. This is the short name, except when two pills in the same card would show
  the same short name (compared as normalized text). Those pills show their full name instead.
- **Alert status**: `expired`, `expiringSoon` or `unknown`. `ok` is not an alert.
- **Normalized text**: as in `fixtures/text-normalization.json` (lower case, accents and ligatures folded, whitespace
  collapsed). Comparisons are by code point.

## User stories and acceptance criteria

Requirement IDs: **G** grid of category cards, **N** short names, **K** pills, **F** search and filter menu,
**X** cross-cutting.

### G — Category cards

*As a household member, I want the cabinet laid out as one card per category, so that I see everything we have on
one or two screens and find the right drawer at once.*

1. The Cabinet tab SHALL show one full-width **category card** per non-empty category. A card SHALL show the category's
   icon, its localized label and the total number of boxes it holds after the current filters ("1 boîte",
   "5 boîtes", plural-aware). Empty categories SHALL not be shown, as before.
2. Cards SHALL be ordered by their **localized label compared as normalized text**, with *Other* and then
   *Uncategorized* last. In French this gives Allergies, Bouche et dents, Digestion, Douleur et fièvre, Muscles et
   articulations, Nez gorge oreilles, Peau et plaies, Trousse de secours, Vitamines et minéraux, Yeux, Autres,
   Non classé. English has its own order. The order SHALL change when the app's language changes.
3. Every category SHALL have its own **icon**, the same concept on both platforms (SF Symbols on iOS, Material
   Symbols on Android). The icon SHALL be decorative: the label carries the meaning, and assistive technologies SHALL
   not read the icon.
4. A card's header SHALL be exposed as a heading to VoiceOver and TalkBack, read as the label and then the box count.
5. With 1 000 boxes, the Cabinet tab SHALL scroll without dropped frames. Cards SHALL be laid out lazily, so that a
   card off screen is not measured. Grouping and ordering SHALL never run on the main thread.

### N — Short names

*As a household member, I want to read the medicine's name without the pharmaceutical form, so that more medicines
fit on screen and I still recognise each box.*

1. A pill SHALL show the **displayed name** of its product row (short name, or the full name when two pills in the
   same card share a short name).
2. The short-name rule and the collision rule SHALL live in both cores and pass every case of a shared fixture under
   `fixtures/` on both platforms. The cases SHALL include a decimal comma ("DIPROSONE 0,05 %, lotion"), no comma, a
   comma with no space, a leading `", "`, whitespace, a manually entered name, and two forms of the same product.
3. A name SHALL never be changed in storage, in the export or on the box detail screen. The short name is a display
   value only.
4. The pill's accessibility label SHALL use the **full name**, never the short name.

### K — Pills

*As a household member, I want to see each medicine with how many boxes we have, and to spot at once the ones that
are no longer safe, so that I know what we have and what to deal with.*

1. Each product row SHALL be drawn as a **pill** holding the displayed name and the box count ("×3"; "×1" for a
   single box). The pill SHALL show no date and no *OK*.
2. Pills SHALL flow left to right and wrap onto as many lines as needed within their card.
3. Within a card, pills SHALL be ordered by **displayed name compared as normalized text**, then by the full name
   compared as normalized text, then by the first box id. The order SHALL be identical on both platforms and covered
   by the shared cabinet-rows fixture.
4. WHEN a pill's row status is an **alert status**, the pill SHALL show that status's icon (the icon already used for
   that status in the app) and a tint for that status. The status SHALL not be carried by colour alone: the icon
   shape differs per status. Text and icon SHALL meet 4.5:1 contrast against the tint in light and dark mode.
5. WHEN a pill's row status is `ok`, the pill SHALL be neutral, with no icon and no status text.
6. A row with one box of unknown expiry among `ok` boxes SHALL be marked *unknown*. A row SHALL never be drawn as
   neutral while one of its boxes has an alert status. This follows from the row status rule, which is unchanged.
7. The pill's accessibility label SHALL read the full name, the box count ("3 boîtes") and, for an alert, the status
   text ("Périmé", "Bientôt périmé", "Date inconnue"). The pill SHALL be a single button.
8. A long displayed name SHALL wrap onto at most **two lines**, then end with an ellipsis. The box count SHALL always
   stay visible.
9. Each pill SHALL be a touch target of at least **44 × 44 pt** on iOS and **48 × 48 dp** on Android, even when it
   draws smaller.
10. WHEN the user taps a pill, the system SHALL open that product's boxes, or the box detail directly when the row
    holds one box (unchanged from `cabinet-inventory` P).
11. Text SHALL follow Dynamic Type up to accessibility sizes on iOS and font scale up to 200 % on Android. Pills SHALL
    keep wrapping, never clip, and never overlap.

### F — Search field and filter menu

*As a household member, I want search and filters to take one line, so that the medicines get the screen.*

1. Under the tab's title, the system SHALL show the **search field** and, **on the same line to its right**, a
   **filter button** (icon only, accessibility label "Filtres").
2. The number of boxes matching the search and filters (C7) SHALL be shown next to the title, not in the list.
3. WHEN the user taps the filter button, the system SHALL open a **menu** with a **Location** section (*All
   locations* and each location that is not deleted, in the household's order), an **Expiry** section (*All*,
   *Expired*, *Expiring soon*, *Expiry unknown*, *OK*, each with its status icon) and, when a filter is active,
   **Clear filters**. The current choice in each section SHALL be marked. The selection and combination rules are
   unchanged (S3–S5).
4. WHILE at least one filter is active, the filter button SHALL look selected (filled), and its accessibility value
   SHALL say how many filters are active. One **chip** per active filter SHALL show under the search field (location
   name or status, with its icon). Tapping a chip's remove control SHALL clear that filter only. The chip row SHALL not
   take any space when no filter is active.
5. The old filter bar (the two menus and the "Filters: … / Clear" text) SHALL be removed.
6. Searching SHALL filter pills as before (S1). A card with no remaining pill SHALL disappear. WHEN nothing is left,
   the existing "no results" state SHALL show (S6).

### X — Cross-cutting

1. Every new or changed user-facing string SHALL exist in French and English on both platforms with identical
   wording (the string checks and `check-string-parity.py` pass).
2. The loading, error, empty-cabinet and "no results" states SHALL behave as before.
3. No new permission, no network, no new stored data, no schema change and no export change. Names, lots and serials
   SHALL never be logged.
4. `docs/cabinet.md` SHALL describe the new layout, the short-name rule and the new orders.
5. Each app SHALL get a **minor** version bump with a changelog entry.

## Out of scope

- Changing the search, location filter, status filter or grouping rules.
- Changing a medicine's capitalisation (BDPM names are upper case and are shown as stored).
- Two-column or masonry layouts (rejected in the challenge).
- The product-boxes screen, box detail, the Expiring tab, the Scan tab and Settings.
- Collapsing or reordering categories by hand, favourites, or a per-user sort setting.
- Database, migration and export format changes.
