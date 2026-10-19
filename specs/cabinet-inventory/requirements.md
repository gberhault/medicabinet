# Cabinet inventory, locations and stock movements — Requirements

Status: **Draft — awaiting review**
Epic: #17 · Children: #18, #19, #20, #21 · Branch: `feature/17-cabinet-inventory` · Platforms: iOS + Android

## Introduction

Once the boxes are in the app (epic #12), the household needs to use it to answer everyday
questions: *do we have something for a fever, where is it, is it still good?* It also has
to keep the cabinet true as boxes are used up or thrown away. This epic fills the Cabinet tab
with a list of what is in the cabinet, grouped by category, searchable and filterable by
location and expiry status. It adds a box detail screen to correct a box, mark it opened, or
take it out. It lets the household manage its locations (bathroom, kitchen, car, travel
kit…), and it brings removal up to the speed of intake: scan a box to take it out, with
**Undo**.

> We are building the Cabinet tab (a list with one row per product, search, location and
> expiry-status filters), box detail (edit, opened, used up / thrown away / added by mistake,
> all with Undo), locations management (create, rename, reorder, default, delete with move
> and tombstone, schema v3), and removal by scan (a Remove mode on the Scan tab with one
> reason per session) on iOS and Android, so that the household always knows what it has,
> where it is and whether it is still safe to use. We are **not** building the expiry-status
> rule or the "expiring soon" setting (#23), the category taxonomy or classifier (#10), the
> Expiring tab (#24), a history of removed boxes, unit counts, or export of the new schema
> (#26).

### Decisions taken in the challenge (2026-10-19)

1. **Sequencing.** This spec is written now; implementation starts only once epic #12 is on
   `develop`, which provides schema v2 (`box.deletedAt`, #40), the expiry field, the scanner
   adapters and *Put back*. The branch is rebased on `develop` before the first code
   commit.
2. **Expiry status comes from #23.** Status labels and the status filter call #23's core rule
   (with the user's "expiring soon" window); this epic does not define it.
3. **Category editing is blocked by #10.** It is specified here (B4); its task waits for the
   taxonomy, like bulk-scan T29 waits for #11.
4. **One row per product** in the Cabinet list, grouped by category; a row opens the
   product's boxes, then box detail.
5. **Deleting a location** moves its boxes to a location the user chooses (the default is
   pre-selected), then tombstones the location (`location.deletedAt`, **schema v3**). The
   default location cannot be deleted.
6. **Removal by scan** is an **Add / Remove** mode on the Scan tab, always opening in Add,
   with a removal reason chosen once per session (like the session location) and **Undo** per
   box.
7. **Undo, not confirmation dialogs**, for box removal and deletion. The only confirmation
   dialog is for deleting a location that still holds boxes.
8. **Version**: 0.3.0 on both apps.

## Glossary

- **In the cabinet** — a box that is neither removed nor deleted.
- **Removed** — taken out as **used up** or **thrown away** (a stock movement; `RemovalReason`).
  The box stays in the database (for *Put back* and export) but is no longer shown.
- **Deleted** — added by mistake (tombstone `box.deletedAt`, from #12). Invisible everywhere.
- **Deleted location** — a tombstoned location (`location.deletedAt`): invisible everywhere,
  kept so that removed boxes still point at a row.
- **Product key** — what makes boxes "the same product" in the list: the GTIN when the box
  has one, otherwise its normalized name.
- **Product row** — one row of the Cabinet list: all boxes in the cabinet with the same
  product key and the same category.
- **Normalized text** — lower-cased, accents and ligatures folded (`é`→`e`, `œ`→`oe`),
  whitespace collapsed; defined by a shared fixture.
- **Expiry status** — `expired`, `expiringSoon`, `ok` or `unknown`, computed by #23's rule.
  **Urgency order**: `expired` > `expiringSoon` > `unknown` > `ok`.
- **Remove session** — a scan session in Remove mode, from switching to Remove until **Done**,
  switching back to Add, or leaving the tab.

## User stories and acceptance criteria

Requirement IDs: **L** locations, **C** Cabinet list, **S** search and filters, **P** product
boxes, **B** box detail, **R** removal by scan, **V** schema v3, **X** cross-cutting.

### L — Locations management (#18)

*As a household member, I want to name the places where we keep medicines, so that I know
where to look for a box and can put it back in the right place.*

1. Settings SHALL contain a **Locations** screen listing every location that is not deleted,
   in the household's order (`sortOrder`, then name), with the default location marked by
   text or an icon (not colour alone).
2. WHEN the user adds a location, the system SHALL require a name of 1–50 characters after
   trimming, and SHALL reject a name equal, as normalized text, to another location that is
   not deleted ("Cuisine" vs "cuisine " vs "Cuísine"), saying why. The new location SHALL be
   placed last.
3. WHEN the user renames a location (the default included), the same rules as L2 SHALL apply.
   Boxes SHALL keep their location; only the name shown changes.
4. The user SHALL be able to reorder locations by dragging, and with an accessible
   alternative (VoiceOver / TalkBack "move up / move down" actions). The order SHALL be used
   by every location picker (scanner, box detail, filters, deletion destination).
5. WHEN the user makes another location the default, the system SHALL make it the only
   default. No box SHALL move.
6. The default location SHALL NOT be deletable; its delete action SHALL be absent or disabled
   with the explanation "Choose another default location first". (As a consequence there is
   always at least one location.)
7. WHEN the user deletes a location with no box in the cabinet, the system SHALL delete it
   (tombstone) and offer **Undo**, which restores it in the same place in the order.
8. WHEN the user deletes a location holding boxes in the cabinet, the system SHALL show a
   confirmation that states how many boxes it holds and asks where to move them (every other
   location, the default pre-selected). **Move and delete** SHALL move those boxes and
   tombstone the location **in one transaction**; **Cancel** SHALL change nothing. A failure
   SHALL leave every box and the location unchanged and show an error.
9. Removed boxes of a deleted location SHALL keep pointing at it (their history is not
   rewritten). WHEN such a box is put back by a scan (#12 I3), it SHALL go to the session
   location as already specified.
10. A deleted location SHALL disappear from every picker, filter and list, and its name SHALL
    be free for a new location. WHEN the scanner's remembered session location or the Cabinet
    location filter refers to a deleted location, the system SHALL fall back to the default
    location / "All locations".
11. Location names SHALL be shown exactly as typed (trimmed); the normalization is only used
    for comparison.

### C — Cabinet list (#19)

*As a household member, I want to see everything in the cabinet at a glance, organised the
way I think about it, so that I find a medicine in seconds and see what is no longer safe.*

1. The Cabinet tab SHALL list the boxes in the cabinet (not removed, not deleted), as
   **product rows** grouped under their **category** heading. Categories SHALL follow the
   taxonomy's order (#10) with *Uncategorized* last; until #10 lands, every box is in
   *Uncategorized*. Empty categories SHALL not be shown.
2. Boxes SHALL form one product row when they share the product key (GTIN, else normalized
   name) and the category. The row SHALL show the name of the most recently added of its
   boxes. A product whose boxes are in two categories (user override) SHALL appear once in
   each.
3. Each product row SHALL show: name, number of boxes (plural-aware: "1 boîte", "3 boîtes"),
   the earliest known expiry date (formatted for the locale, month-year for month
   precision), and the **row status** — the most urgent expiry status among its boxes, as
   text and icon. WHEN no box of the row has a known expiry, the row SHALL say "expiry
   unknown". A row SHALL never show `ok` while one of its boxes has an unknown expiry.
4. Within a category, rows SHALL be ordered by row status (urgency order), then earliest
   known expiry (rows with none last), then name (locale-aware, accent-insensitive).
5. Grouping, row status and ordering SHALL live in both cores and pass every case of
   `fixtures/cabinet-rows.json` on both platforms.
6. The list SHALL update by itself when boxes change elsewhere (a scan, a removal, an import),
   without a manual refresh.
7. The list SHALL show a header with the number of boxes matching the current search and
   filters.
8. WHILE the cabinet is loading for the first time, the system SHALL show a loading state; WHEN
   loading fails, an error state with **Retry**.
9. WHEN the cabinet is empty, the system SHALL show an empty state explaining how to add
   boxes, with a button that opens the Scan tab.
10. With 1 000 boxes, the list SHALL scroll without dropped frames, and grouping, search and
    filtering SHALL never run on the main thread.

### S — Search and filters (#19)

*As a household member, I want to type a few letters or narrow the list to one place or to
what has expired, so that I find what I need even when the cabinet is large.*

1. WHEN the user types a search query, the list SHALL keep only rows whose name contains
   **every** word of the query as normalized text ("doli 1000" finds "DOLIPRANE 1000 mg";
   "efferalgan" finds "EFFÉRALGAN"). An empty or whitespace-only query SHALL filter nothing.
2. Name matching SHALL live in both cores and pass every case of
   `fixtures/text-normalization.json` on both platforms (the same normalization serves L2).
3. The user SHALL be able to filter by **one location** or "All locations" (the default). With
   a location selected, rows SHALL count and show only the boxes in that location.
4. The user SHALL be able to filter by **expiry status**: All (default), Expired, Expiring
   soon, Expiry unknown, OK. A row SHALL be kept when at least one of its boxes has that
   status; the row then counts only those boxes.
5. Search, location and status SHALL combine (all must match). Active filters SHALL be
   visible as text, with a one-tap way to clear them.
6. WHEN search or filters leave nothing to show, the system SHALL say so and offer to clear
   them (distinct from the empty-cabinet state C9).
7. Search text and filters SHALL last while the app runs and SHALL NOT be stored, logged or
   exported.

### P — A product's boxes (#19, #20)

*As a household member, I want to see each box of a product, so that I use the oldest one
first and find the right drawer.*

1. WHEN the user opens a product row with several boxes, the system SHALL list them with,
   for each one, its expiry (or "expiry unknown") with status text and icon, its location,
   and "opened" when it is opened. They SHALL be ordered by expiry, earliest first, with
   unknown expiry last. The current location and status filters SHALL apply.
2. WHEN the row has a single box, opening it SHALL go straight to that box's detail.
3. WHEN the last box of the list leaves the cabinet, the system SHALL return to the Cabinet
   list.

### B — Box detail (#20)

*As a household member, I want to correct a box, mark it opened and take it out when it is
used up or thrown away, so that the cabinet stays true — and undo it if I tap by mistake.*

1. Box detail SHALL show the name, the code (the 13-digit CIP13 / EAN-13 when the GTIN-14
   starts with `0`, else the GTIN-14; "no code" when absent), the category, the location, the
   expiry with its status as text and icon (or "expiry unknown", never OK), "opened", the lot
   when known, and the date it was added. The serial SHALL not be shown.
2. WHEN the user edits the box, the system SHALL allow changing the name (1–200 characters,
   trimmed), the expiry (with the expiry field from #12: month/year/optional day, or
   **Date not printed** for unknown; never a guessed or pre-filled date when there was none)
   and the location (pickers list locations that are not deleted). **Save** SHALL store the
   changes; **Cancel** SHALL discard them. The GTIN, lot and serial SHALL not be editable.
3. WHEN the user toggles **Opened**, the system SHALL save it immediately; toggling again
   undoes it.
4. *(Blocked by #10.)* The user SHALL be able to change the category from the taxonomy;
   doing so SHALL mark it user-chosen (`categoryOverridden`), and **Automatic category**
   SHALL give it back to the classifier.
5. WHEN the user chooses **Used up** or **Thrown away**, the system SHALL remove the box with
   that reason and the current date, leave the screen (P3), and show a message with
   **Undo**, which puts the box back exactly as it was (same location, expiry, opened flag).
6. WHEN the user chooses **Added by mistake**, the system SHALL delete the box (tombstone,
   #12) and show a message with **Undo**, which restores it. The wording SHALL make clear
   this is not a stock movement. WHEN the box's serial is taken again in the meantime, Undo
   SHALL say it cannot restore the box (#12 flow 6).
7. Removal and deletion SHALL never ask for confirmation, and the Undo message SHALL stay at
   least as long as the platform's long duration, and remain reachable with VoiceOver /
   TalkBack.
8. No screen SHALL give dosage, indication or treatment advice; the category label is only
   for tidying.

### R — Remove a box by scanning it (#21)

*As a household member, I want to scan the boxes I take out, one after the other, so that
clearing out expired boxes takes as little time as stocking the cabinet.*

1. The Scan tab SHALL offer an **Add / Remove** switch. It SHALL be in **Add** every time the
   tab is opened and at every launch; Remove SHALL never be remembered.
2. WHILE in Remove mode, the screen SHALL say so in its title, with an icon and a colour, and
   VoiceOver / TalkBack SHALL announce the mode change.
3. Remove mode SHALL show the **session reason**, **Used up** or **Thrown away**, changeable in
   one tap. It SHALL be pre-selected to the reason last used in Remove mode, else Used up,
   and SHALL apply to boxes removed afterwards. (UI preference, not cabinet data.)
4. Scanning, debouncing, parsing, camera lifecycle, permission states and "code not
   recognised" SHALL behave exactly as in Add mode (#12 C, D, P).
5. The match decision SHALL live in both cores and pass every case of
   `fixtures/scan-removal.json` on both platforms:
   1. WHEN the scan has a serial and a box in the cabinet has the same GTIN and serial, that
      box SHALL be removed.
   2. WHEN the scan has a serial and the matching box is already removed, nothing SHALL
      change and the system SHALL say "already removed".
   3. Otherwise, the **candidates** SHALL be the boxes in the cabinet with that GTIN — when
      the scan has a serial, only those with no serial (a box with a different serial is
      another box). Candidates SHALL be ordered: same expiry as the scan first (when the scan
      has one), then earliest expiry, unknown expiry last.
   4. WHEN there is exactly one candidate, it SHALL be removed.
   5. WHEN there are several, the system SHALL ask which one (R7).
   6. WHEN there are none, nothing SHALL change and the system SHALL say "not in the cabinet".
      It SHALL NOT offer to add the box.
   7. Deleted boxes SHALL never match.
6. WHEN a box is removed, the system SHALL within **300 ms** of the scan give a haptic and
   show its name, expiry, location and the reason, with **Undo** (and play the sound when
   it is on). The haptic and the message SHALL differ from Add mode's, and a non-removal
   (R5.2, R5.6) SHALL use the "not added" haptic and message style.
7. The candidate picker SHALL show each candidate's expiry (or "expiry unknown"), location
   and "opened", with the first candidate pre-selected. **Remove** SHALL remove the selected
   box; **Cancel** SHALL remove nothing. WHILE it is open, recognitions SHALL be ignored.
8. The session list SHALL show the boxes removed in the Remove session, newest first, each
   with **Undo**. **Done** SHALL show a summary (boxes used up, boxes thrown away) and end
   the session. Removals are saved at once; leaving without Done SHALL lose nothing.
9. Removal by scan SHALL use the same removal operation as B5 (reason + date, undoable).

### V — Schema v3: location tombstone

*As a household member, I want to delete a location without losing the history of the boxes
that were in it, and without breaking a future sync.*

1. A v3 migration, identical on both platforms and checked by `fixtures/schema-v3.sql` and
   `fixtures/schema-v3.json`, SHALL add `location.deletedAt` (nullable date). v1 and v2 SHALL
   stay unchanged.
2. WHEN an existing cabinet is opened, the system SHALL back it up and migrate it with the
   existing bootstrap rules (backup first; failure restores the copy and shows the error).
3. Every location query SHALL ignore deleted locations. New repository operations SHALL
   rename, reorder, set the default, delete a location (with or without moving its boxes, in
   one transaction), undo a location deletion, list the boxes in the cabinet with a GTIN, and
   observe the boxes in the cabinet as a stream of changes. Each SHALL be covered by a shared
   repository-contract check on the in-memory fake and the real store of both platforms.
4. The repository SHALL refuse to tombstone the default location with a typed error, and the
   rule SHALL hold whatever the caller (contract check).

### X — Cross-cutting

1. **Privacy**: product names, search text, lots and serials SHALL never be logged; failures
   log their kind only. No new permission or network access; the only new stored data are
   the v3 column and one UI preference (the last removal reason, not exported).
2. **Performance**: Cabinet list of 1 000 boxes without dropped frames; grouping + filter +
   search on 1 000 boxes ≤ 100 ms off the main thread; scan → removal confirmation < 300 ms
   with a 1 000-box cabinet; no I/O on the main thread; every query indexed.
3. **Accessibility**: every control labelled for VoiceOver/TalkBack; statuses carry text and
   icon, never colour alone; 44 pt / 48 dp targets; Dynamic Type and 200 % font scale (rows
   wrap, nothing truncated that carries a status); swipe and drag actions have accessible
   alternatives; Undo messages reachable with assistive technologies; WCAG AA contrast in
   light and dark mode.
4. **i18n**: every new string in French and English on both platforms with the same wording;
   plurals for counts; dates formatted with the user's locale.
5. **Docs**: `docs/cabinet.md` (list, search, filters, box detail, removal), `docs/locations.md`,
   `docs/bulk-scan.md` (Remove mode), `docs/architecture.md` (schema v3, observation),
   `fixtures/README.md` (new fixtures and names).
6. **Export**: the export epic (#26) SHALL carry `location.deletedAt`; this epic changes no
   export format (none has shipped).
7. **Version**: iOS 0.3.0 and Android 0.3.0 (build numbers +1), with changelog entries.
8. Each feature SHALL have success, failure and edge-case tests on both platforms; core
   coverage targets 100 % of branches, new app code 90 %.

## Dependencies

| Needs | From | Used by |
|-------|------|---------|
| Schema v2 `box.deletedAt`, delete / undo delete, `duplicateSerial` | #40 (epic #12) | B6, R5.7, V |
| Expiry field (month / year / optional day / Date not printed) | #12 T17/T23 | B2 |
| Scanner adapters, debouncer, parser, session UI, *Put back* | #12 | R, L9 |
| Expiry status rule and "expiring soon" window | #23 (epic #22) | C3–C5, S4, P1, B1 |
| Category taxonomy, labels and order | #10 (epic #7) | C1, B4 |

## Out of scope

- The expiry-status rule and the "expiring soon" setting (#23); the Expiring tab (#24);
  notifications (#25).
- The category taxonomy and classifier (#10); category editing ships only when #10 has.
- A history of removed or deleted boxes, statistics, or a "restock" list.
- Creating locations from the scanner or box detail pickers (#12 decision 7 stands; Settings
  only).
- Unit counts, partial use, dates of opening or "use within N weeks after opening" (treatment
  territory).
- Bulk actions on several boxes at once (select many → remove); removal by scan covers the
  bulk case.
- Export / import of the new column (#26); multi-phone sync.

## Open points to confirm in the review

1. **Row ordering (C4)**: urgency first (expired rows at the top of each category), as
   proposed, or alphabetical within a category, leaving urgency to the Expiring tab (#24)?
2. **Default removal reason (R3)**: last used, else **Used up** — or always start on
   **Thrown away**, since clearing out expired boxes is the main bulk-removal case?
3. **Single box skips the list (P2)**: open its detail directly, as proposed, or always show
   the product's box list for consistent navigation?
