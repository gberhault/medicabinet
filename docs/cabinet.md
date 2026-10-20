# The cabinet: list, search, filters and box detail

The **Cabinet** tab answers *do we have something for this, where is it, is it still good?* It lists what is in the
cabinet, lets the household correct a box, mark it opened and take it out — and every removal can be undone.
Specs: [`specs/cabinet-inventory/`](../specs/cabinet-inventory/) (epic #17). Both apps behave the same; the grouping,
ordering and search rules are written once as shared fixtures (see below). Removing boxes by scanning them is
described in [`bulk-scan.md`](bulk-scan.md#remove-mode-taking-boxes-out-by-scanning); locations in
[`locations.md`](locations.md).

## What the user sees

1. **The list.** One row per product, grouped under its category (the taxonomy's order, *Uncategorized* last, empty
   categories hidden). A product is the same product when it has the same GTIN (else the same normalized name) and
   category; a product whose boxes sit in two categories (the user changed one) appears once in each. A row shows the
   name of the most recently added box, the number of boxes ("1 box", "3 boxes"), the earliest known expiry and the
   **row status** — the most urgent status among its boxes, as text and icon. A row never says *OK* while one of its
   boxes has an unknown expiry; with no known expiry at all it says *Expiry unknown*.
2. **Order.** Inside a category: expired first, then expiring soon, unknown, OK; then the earliest known expiry (rows
   with none last); then the name compared as normalized text (accent- and case-insensitive).
3. **Search.** Every word typed must appear in the product name ("doli 1000" finds "DOLIPRANE 1000 mg", "efferalgan"
   finds "EFFÉRALGAN"). An empty or blank query filters nothing.
4. **Filters.** One location (or all) and one expiry status (all, expired, expiring soon, expiry unknown, OK). They
   combine with the search; active filters are shown as text with a one-tap **Clear**. A row is kept when at least one
   of its boxes matches, and then counts only those boxes. Search text and filters last while the app runs; they are
   never stored, logged or exported.
5. **Header.** The number of boxes matching the search and filters.
6. **States.** Loading, an error with **Retry**, an empty cabinet (with a button that opens the Scan tab) and "no
   results" (with a button that clears the search and filters).
7. **A product's boxes.** Opening a row with several boxes lists them (expiry with status, location, *Opened*;
   earliest first, unknown last, current filters applied). A row with one box opens its detail directly. When the last
   box of the list leaves the cabinet the app returns to the list.
8. **Live.** The list follows the database: a scan on the Scan tab, a removal or an import shows up by itself.

## Box detail

- Shows the name, the code (the 13-digit CIP13 / EAN-13 when the GTIN-14 starts with `0`, else the GTIN-14, or *No
  code*), the category, the location, the expiry with its status (text and icon; an unknown expiry is never *OK*),
  *Opened*, the lot when known and the date added. **The serial number is never shown.**
- **Edit** changes the name (1–200 characters, trimmed), the expiry (month, year, optional day — or **Date not
  printed** for unknown; a date is never guessed or pre-filled) and the location (locations that are not deleted).
  The code, the lot and the serial cannot be edited. **Cancel** discards.
- **Category.** The user can pick a category from the taxonomy; the box is then marked as set by the user and the
  classifier never moves it again. **Automatic category** gives it back to the classifier (the reference category of its
  code, else *Uncategorized*). Categories are for tidying the cabinet, not for choosing a treatment.
- **Opened** is a switch that saves at once.
- **Take out of the cabinet**: *Used up* or *Thrown away* (a stock movement with today's date) or *Added by mistake*
  (deletes the box without counting it). None asks for confirmation; each shows a message with **Undo** that puts the
  box back exactly as it was (same location, expiry, opened flag). If another box took the same serial in the
  meantime, Undo of a mistaken box says it cannot restore it.

## The rules (shared fixtures)

| Rule | Fixture | Core type (Swift / Kotlin) |
|------|---------|----------------------------|
| Text folding and name search | `fixtures/text-normalization.json` | `TextNormalizer` |
| Rows, sections, row status, order, filters | `fixtures/cabinet-rows.json` | `CabinetRows` |
| Which box a scan removes | `fixtures/scan-removal.json` | `RemovalPolicy`, `ScanRemoval` |
| Expiry status of a box | `fixtures/expiry-status.json` | `ExpiryRule` |

`CabinetRows.build` is a pure function over the boxes in the cabinet (no SQL `GROUP BY`: accent-insensitive matching
and the product key cannot be written identically in SQLite on both platforms). It runs off the main thread; with
1 000 boxes it takes a few milliseconds (budget ≤ 100 ms, asserted by a test on both platforms). The expiry status is
the one rule of epic #22 with the default "expiring soon" window of 60 days, evaluated with the injected clock; rows
are recomputed when the day changes.

## Data and privacy

No new permission, no network. The box detail and the list never log names, lots or serials. The only stored data this
epic adds are `location.deletedAt` (schema v3) and the scanner's last removal reason (a UI preference, not exported).
The export (#26) must carry `location.deletedAt` and the box tombstones.

## Where the code is

| | iOS | Android |
|--|-----|---------|
| List, filters, product boxes | `ios/MediCabinet/Sources/Features/Cabinet/` | `android/app/src/main/kotlin/com/medicabinet/app/features/cabinet/` |
| Pure rules | `ios/Packages/MediCabinetCore/Sources/MediCabinetCore/Cabinet/` | `android/core/src/main/kotlin/com/medicabinet/core/cabinet/` |
| Undo message | `Features/Common/UndoBanner.swift`, `UndoCenter.swift` | `features/common/` (`UndoController`, snackbar in `MainScaffold`) |
