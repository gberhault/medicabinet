# The cabinet: list, search, filters and box detail

The **Cabinet** tab answers *do we have something for this, where is it, is it still good?* It lists what is in the
cabinet, lets the household correct a box, mark it opened and take it out — and every removal can be undone.
Specs: [`specs/cabinet-inventory/`](../specs/cabinet-inventory/) (epic #17) and, for the cards, pills and filter menu,
[`specs/cabinet-grid/`](../specs/cabinet-grid/) (#48). Both apps behave the same; the grouping,
ordering and search rules are written once as shared fixtures (see below). Removing boxes by scanning them is
described in [`bulk-scan.md`](bulk-scan.md#remove-mode-taking-boxes-out-by-scanning); locations in
[`locations.md`](locations.md).

## What the user sees

1. **Category cards.** One full-width card per category that has boxes, in **A–Z order of the label in the app's
   language** (accents and case ignored), *Other* and then *Uncategorized* always last. A card shows the category's
   icon, its label and its number of boxes. Inside it, one **pill** per product (the same product means the same GTIN,
   else the same normalized name, and the same category): its **short name** and its box count ("×3"). A product whose
   boxes sit in two categories (the user changed one) appears once in each. The card's icon is decorative.
2. **Pills.** The short name is the stored name up to its first ", " ("DOLIPRANE 1000 mg, comprimé" is shown
   "DOLIPRANE 1000 mg"; a decimal comma like "0,05 %" is never cut). When two products of the same card have the same
   short name, both show their full name. The stored name never changes. Pills are in A–Z order of the shown name
   (accent- and case-insensitive). **No date and no "OK" in the list**: only a product that is expired, expiring soon
   or of unknown expiry is marked, with an icon and a tint (never colour alone), and the product's status is its worst
   box. A pill with a box of unknown expiry is never plain. A plain pill is a product with nothing to report. A pill
   draws about 32 pt / 32 dp high and takes taps over 44 pt / 48 dp; a long name wraps onto two lines, then ends with
   an ellipsis, and the count always stays visible. VoiceOver and TalkBack read the full name, the count and the
   status ("Doliprane 1000, 2 boxes, Expired").
3. **Search.** Every word typed must appear in the product name ("doli 1000" finds "DOLIPRANE 1000 mg", "efferalgan"
   finds "EFFÉRALGAN"). An empty or blank query filters nothing. The search field has a clear button.
4. **Filters.** A **filter button** sits to the right of the search field, on the same line. It opens one menu with a
   *Location* section (all locations or one) and an *Expiry* section (all, expired, expiring soon, expiry unknown, OK;
   each with its icon), and *Clear filters* while one is on. They combine with the search; a card keeps a product
   when at least one of its boxes matches, and then counts only those boxes. While a filter is on, the button looks
   filled and says how many are on, and one **chip** per filter shows under the search field; a chip's x removes
   only that filter. Search text and filters last while the app runs; they are never stored, logged or exported.
5. **Header.** The number of boxes matching the search and filters, next to the title. The search field, the filter
   button and the chips are the only thing between the title and the cards.
6. **States.** Loading, an error with **Retry**, an empty cabinet (with a button that opens the Scan tab) and "no
   results" (with a button that clears the search and filters).
7. **A product's boxes.** Tapping a pill with several boxes lists them (expiry with status, location, *Opened*;
   earliest first, unknown last, current filters applied). A pill with one box opens its detail directly. When the last
   box of the list leaves the cabinet the app returns to the list.
8. **Launch.** The app opens on this tab (issue #53); a notification tap that targets another tab, such as the monthly
   reminder opening Expiring, wins over it, also on a cold start.
9. **Live.** The list follows the database: a scan on the Scan tab, a removal or an import shows up by itself.

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
| Rows, sections, row status, displayed name, order, filters | `fixtures/cabinet-rows.json` | `CabinetRows` |
| Short name of a product | `fixtures/short-names.json` | `ShortName` |
| Order of the category cards (A–Z by label) | `fixtures/category-order.json` | `CategoryOrder` |
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
| Header, cards, pills, product boxes | `ios/MediCabinet/Sources/Features/Cabinet/` (`CabinetHeader`, `CategoryCardView`, `ProductPill`) | `android/app/src/main/kotlin/com/medicabinet/app/features/cabinet/` (`CabinetHeader`, `CategoryCard`, `ProductPill`) |
| Category icons | `Features/CategoryIcon.swift` | `features/CategoryIcons.kt` |
| Pure rules | `ios/Packages/MediCabinetCore/Sources/MediCabinetCore/Cabinet/` | `android/core/src/main/kotlin/com/medicabinet/core/cabinet/` |
| Undo message | `Features/Common/UndoBanner.swift`, `UndoCenter.swift` | `features/common/` (`UndoController`, snackbar in `MainScaffold`) |
