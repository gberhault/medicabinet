# Cabinet grid — Design

Status: **Approved 2026-10-20**
Requirements: [`requirements.md`](requirements.md) (approved 2026-10-20) · Issue: #48 · Platforms: iOS + Android

## 1. Overview

Only the Cabinet list changes. The data pipeline stays the same: repository → boxes → `CabinetRows.build`, off the main
thread → snapshot → view. Two small pure rules are added to both cores, and one rule there changes:

| Rule | Core | Fixture |
|------|------|---------|
| **New:** short name of a medicine name | `ShortName.of(_:)` | `fixtures/short-names.json` (new) |
| **New:** category order from localized labels (A–Z, *Other* then *Uncategorized* last) | `CategoryOrder.alphabetical(labels:)` | `fixtures/category-order.json` (new) |
| **Changed:** row order inside a category, plus the displayed name with its collision rule | `CabinetRows.build` | `fixtures/cabinet-rows.json` (updated) |

Section ordering inside `CabinetRows.build` does not change. It still follows the `categoryOrder` it is given (unlisted
categories by key, *Uncategorized* last). What changes is that the app now passes in the alphabetical order instead of
the taxonomy order. Grouping, row status, filters and search don't change either.

## 2. Core (both platforms, twins)

### 2.1 `ShortName`

```swift
public enum ShortName {
    /// The name cut before its first ", " (comma + space), trimmed; the whole trimmed name when there is no ", "
    /// or the cut leaves nothing. "DIPROSONE 0,05 %, lotion" → "DIPROSONE 0,05 %".
    public static func of(_ name: String) -> String
}
```

```kotlin
object ShortName { fun of(name: String): String }
```

"Trimmed" means the same thing as in `TextNormalizer.trimmed`: Unicode whitespace, using the same definition the
`text-normalization` fixture already pins. The comma is U+002C only and the space is U+0020 only. Every other
character is kept, so a case shows that "A,B" and "A ,B" are not cut.

### 2.2 `CategoryOrder`

```swift
public enum CategoryOrder {
    /// Categories sorted by their label as normalized text (code point), ties by raw id; `other` then
    /// `uncategorized` last whatever their label. Ids missing from `labels` are left out (callers pass every
    /// displayed category — `Taxonomy.ordered`).
    public static func alphabetical(labels: [CategoryID: String]) -> [CategoryID]
}
```

Comparisons use `TextNormalizer.normalize` and code-point order. This avoids `localizedCompare` / `Collator`, which
can differ between platforms and OS versions. For the real labels this matches what a French or English reader
expects, because the labels hold only letters, spaces and accents, which normalization folds.

### 2.3 `CabinetRows` changes

- `ProductRow` gains **`displayName: String`**. Both apps' fixture support gains `displayName` on expected rows; the
  field is required in every case.
- After the rows of a section are built, the short name of each row is computed. Rows whose short names are equal as
  normalized text get `displayName = name` (the full name). The others get `displayName = ShortName.of(name)`.
- **Row order**: first the normalized `displayName`, then the first box id. Urgency and earliest expiry no longer
  affect row order. A tie-break on the full name was dropped: with the collision rule, equal displayed names always
  have equal full names as normalized text, so it could never decide.
- The order of boxes inside a row (expiry, unknown last, creation order, id) and `earliestExpiry` stay as they are.
  The product-boxes screen still uses that box order. `earliestExpiry` stays in the type because the fixture pins it
  and it costs nothing, but the list no longer shows it.

### 2.4 Fixtures

- **`fixtures/short-names.json`** (new), with `{name, input, expect}` cases. At least these: a BDPM form
  ("DOLIPRANE 1000 mg, comprimé"), a decimal comma ("DIPROSONE 0,05 %, lotion"), several `", "` (cut at the first), no
  comma, a comma without a space ("A,B"), a space before the comma ("A ,B"), a leading `", x"` (whole name), only a
  comma and spaces, surrounding whitespace, non-breaking space after the comma (not cut), a manually entered name,
  and an empty string.
- **`fixtures/category-order.json`** (new), with cases `{name, labels: {id: label}, expect: [id]}`. It includes the
  **real French and English labels** of the shipped taxonomy, so a label change that reorders the list fails on both
  platforms. It also covers: *Other* and *Uncategorized* labelled to sort first (still last), accents ("Éraflures"
  sorts with E), two equal labels (ties by id), and a single category.
- **`fixtures/cabinet-rows.json`** (updated). Its description changes to the new row order and gains `displayName`.
  Cases:
  - "rows are ordered by status urgency, then earliest expiry, then name" becomes **"rows are ordered by displayed
    name, ignoring status and expiry"**. It uses the same boxes, and the expected order changes to A–Z.
  - "a month-precise expiry sorts as the last day of its month" is rewritten so that both boxes are **in one row**
    (same GTIN). It then checks the box order, where month precision still matters.
  - New: "the displayed name is the short name", "two products with the same short name in a category show their
    full names", "the same short name in two categories is not a collision", "rows with the same displayed name are
    ordered by first box id".
  - Every existing case gets `displayName` added to its expected rows. A one-off script does this and the diff is
    reviewed by hand.
- **`fixtures/README.md`** gains rows for the two new files.
- **`scripts/tests/test-shared-fixtures-break.sh`** gains a broken `short-names` case and a broken `category-order` case.
  The test passes when both suites fail on the broken file.

## 3. iOS

### 3.1 Layers touched

```
Features/Cabinet/
  CabinetView.swift            rewritten: header + ScrollView/LazyVStack of CategoryCardView
  CabinetHeader.swift          new (replaces CabinetFilterBar.swift): title + count, search field + filter Menu, chips
  CategoryCardView.swift       new: icon, label, box count, FlowLayout of ProductPill
  ProductPill.swift            new: one pill (button), status styling, accessibility
  PillStyle.swift              new: per-status colours (asset catalog) and icon (ExpiryStatusStyle.symbol)
  CabinetViewModel.swift       category order from labels; clearLocation()/clearStatus(); activeFilterCount
Features/Common/FlowLayout.swift   new: SwiftUI `Layout` that wraps subviews onto lines
Features/CategoryIcon.swift        new: CategoryID → SF Symbol (next to CategoryLabel.swift)
App/AppEnvironment.swift           `categoryOrder` → `categoryLabel: (CategoryID) -> String` (default CategoryLabel.text)
Resources/Assets.xcassets          PillExpired{Background,Foreground}, PillSoon{…}, PillUnknown{…} (light + dark)
Resources/Localizable.xcstrings    new/removed keys (§7)
```

`CabinetFilterBar.swift` is deleted.

### 3.2 Screen structure

- `NavigationStack` root: `.navigationTitle("cabinet.title")` stays for the back button and VoiceOver, but the root
  hides its navigation bar (`.toolbar(.hidden, for: .navigationBar)`). Pushed screens show their bar as they do today.
- **Header** (`CabinetHeader`, placed with `.safeAreaInset(edge: .top)` so it stays put while the cards scroll):
  1. The title "Armoire" in `.largeTitle.bold()` with the count (`cabinet.count`, existing plural) beside it in
     `.subheadline` secondary. They sit in an `HStack(alignment: .firstTextBaseline)` and wrap into a `VStack` through
     `ViewThatFits` at large sizes. The title carries the `.isHeader` trait.
  2. A **search field** with the filter button on its right. The search field is a `TextField` in a rounded rectangle
     (`.quaternary` fill) with a magnifier, a clear button that appears when text is present, `.submitLabel(.search)`,
     and no autocorrection. **Why not `.searchable`?** On iOS 17 it puts the field in the navigation bar drawer, and a
     toolbar button can only go on the title row, not the search row. F1 requires them on the same line. We lose the
     system search bar's Cancel button and its collapse on scroll. Neither matters on a screen whose header is three
     lines. The keyboard is dismissed on scroll (`.scrollDismissesKeyboard(.immediately)`).
  3. The **filter button** is a `Menu` labelled `Image(systemName: "line.3.horizontal.decrease.circle")`, which turns
     `….circle.fill` when a filter is active, with a 44×44 frame, `accessibilityLabel("cabinet.filter.button")` and
     `accessibilityValue(cabinet.filter.activeCount)`. Its content is a
     `Picker(.inline)` with a **Location** section, a `Picker(.inline)` with an **Expiry** section (each status has
     its `ExpiryStatusStyle.symbol`), and a destructive **Clear filters** button when a filter is active. This is the
     native menu with checkmarks.
  4. **Chips** appear only when a filter is active. Each chip is a capsule button showing an icon (`mappin.and.ellipse`
     or the status symbol), the text, and `xmark`. Tapping it clears that filter. Its accessibility label is
     `cabinet.filter.remove` with the filter's text.
- **Body**: `ScrollView { LazyVStack(spacing: 12) { ForEach(sections) { CategoryCardView } } }` with horizontal padding
  16, or the existing no-results `ContentUnavailableView`. Loading, error and empty-cabinet states are unchanged.

### 3.3 Card and pill

- `CategoryCardView`: a `RoundedRectangle(cornerRadius: 16)` with `.background(.secondarySystemGroupedBackground)`
  on a grouped background. The header row has `CategoryIcon` (decorative, `.accessibilityHidden(true)`), the label in
  `.headline`, and the box count (`cabinet.row.boxes`, existing plural) in `.subheadline` secondary. The header is one
  combined accessibility element with the `.isHeader` trait. Below it, the pills sit in a `FlowLayout(spacing: 6,
  lineSpacing: 0)`.
- `FlowLayout` is a `Layout` that places subviews left to right, wraps when the next one does not fit, and offers
  each subview at most the full width. A pill wider than the card therefore wraps its own text. It is a pure
  measurement function, unit-tested with fixed sizes.
- `ProductPill` is a `Button` with `.buttonStyle(.plain)`. The visual capsule is ≥ 32 pt high: `.subheadline`
  text, 6 pt vertical padding and 12 pt horizontal padding. It is wrapped in `.padding(.vertical, 6)` and
  `.contentShape(Rectangle())`, so the **hit area is ≥ 44 pt** high, and min-width 44. The name has
  `lineLimit(2)`, `truncationMode(.tail)` and `layoutPriority(1)`, and `×N` has `fixedSize()`, so the count is never
  truncated. The status icon comes first, for alerts only. Accessibility: `.accessibilityElement(children: .ignore)`,
  label `cabinet.pill.label` (full name and box count) or `cabinet.pill.labelStatus` (full name, count and status
  text), and the `.isButton` trait.
- Status colours are named colours in `Assets.xcassets` with light and dark variants. They are the same values on
  Android (§4.3, §6).
- The tap goes through `NavigationLink(value:)`, using today's `route(for:)` (one box → `.box`, else `.product`).

### 3.4 ViewModel

- `CabinetViewModel` computes `categoryOrder` with `CategoryOrder.alphabetical(labels:)` over `Taxonomy.ordered`,
  using `environment.categoryLabel`, once per build. That is 12 labels, so it costs nothing, and the build already
  runs off the main actor. iOS relaunches the app when the user changes its language, so the order always follows the
  current language.
- New methods: `clearLocation()`, `clearStatus()`, and `activeFilterCount` (location and status only, since the
  search text has its own clear button). `activeFilterParts` and the old summary text are removed.

## 4. Android

### 4.1 Layers touched

```
features/cabinet/
  CabinetScreen.kt         rewritten: Scaffold(topBar = title + count) → header + LazyColumn of category cards
  CabinetHeader.kt         new (replaces CabinetFilterBar.kt): search field + filter IconButton/DropdownMenu, chips
  CategoryCard.kt          new: icon, label, box count, FlowRow of ProductPill
  ProductPill.kt           new
  PillColors.kt            new: per-status colours, light/dark (same values as iOS)
  CabinetViewModel.kt      setCategoryOrder(); clearLocation()/clearStatus(); activeFilterCount
features/CategoryIcons.kt  new: CategoryID → ImageVector (next to CategoryLabels)
app/AppEnvironment.kt      `categoryOrder` removed (order now comes from the screen)
res/values{,-en}/strings.xml   new/removed keys (§7)
```

`CabinetFilterBar.kt` is deleted.

### 4.2 Screen structure

- **Top bar**: `TopAppBar` whose title is a `Row` holding "Armoire" and the count (`bodyMedium`, `onSurfaceVariant`),
  wrapped in a `FlowRow` so it wraps at 200 % font scale.
- **Header** under the bar, not scrolling:
  - A `Row` with the search field, `OutlinedTextField` with `RoundedCornerShape(50)`, a leading search icon, a
    trailing clear icon when there is text, `singleLine`, `ImeAction.Search` and `weight(1f)`. To its right is the
    filter `IconButton` (48 dp) inside a `BadgedBox`, showing `Icons.Outlined.FilterList`, which becomes a filled
    tonal button when a filter is active, with `contentDescription = cabinet_filter_button` and `stateDescription`
    set to the count.
  - The menu is a `DropdownMenu` anchored to the button. It has section headers (`labelMedium`, not clickable) and
    `DropdownMenuItem`s with a leading check icon for the current choice and a trailing status icon. *Clear filters*
    is shown when a filter is active. Selecting an item closes the menu.
  - **Chips**: a `FlowRow` of `InputChip(selected = true)` with a leading icon and a trailing close icon. Clicking one
    clears that filter. It is only composed when a filter is active.
- **Body**: `LazyColumn` with one `item(key = category)` per section (a card), so off-screen cards are not composed.
  It also holds the no-results state as today. Loading, error and empty-cabinet states are unchanged.
- **Category order**: the composable builds `labels` with `stringResource` for each `Taxonomy.ordered` id and calls
  `CategoryOrder.alphabetical`. It passes the result to `model.setCategoryOrder(order)` from a `LaunchedEffect(order)`.
  A locale change recreates the activity, the labels are resolved again, and the VM rebuilds the list. The VM starts
  from `Taxonomy.ordered` until the first `setCategoryOrder`, which happens before the first frame in practice, and
  its build is debounced like the filters.

### 4.3 Card and pill

- `CategoryCard`: `Card(colors = cardColors(containerColor = surfaceContainerLow), shape = RoundedCornerShape(16.dp))`.
  The header `Row` has the icon (decorative, `contentDescription = null`), the label in `titleSmall`, and the count in
  `bodySmall` `onSurfaceVariant`. The header is merged with `Modifier.semantics(mergeDescendants = true) { heading() }`.
  Below it is `FlowRow(horizontalArrangement = spacedBy(6.dp))` (`@OptIn(ExperimentalLayoutApi::class)` where the BOM
  still marks it experimental).
- `ProductPill`: a `Surface(onClick, shape = CircleShape, color, border)` with `Modifier.minimumInteractiveComponentSize()`,
  which gives a **48 dp touch target** around a visual height of at least 32 dp. Inside is a `Row` with an optional
  status icon (18 dp), a name `Text` (`labelLarge`, `maxLines = 2`, `overflow = Ellipsis`, `weight(1f, fill = false)`)
  and the count `Text` (`×N`, never truncated). Semantics: `clearAndSetSemantics { contentDescription = …;
  role = Role.Button }`, using the same strings as iOS.
- `CategoryIcons` and `PillColors` are plain `when` mappings over `CategoryID` and `ExpiryStatus`. The colours are
  read from `isSystemInDarkTheme()` through `MaterialTheme`.

## 5. Category icons (same concept on both platforms)

| Category | iOS (SF Symbol) | Android (`Icons.Outlined.*`) | Concept |
|----------|-----------------|------------------------------|---------|
| pain-fever | `thermometer.medium` | `Thermostat` | thermometer |
| cold-ent | `nose` | `Air` | breathing |
| digestion | `fork.knife` | `Restaurant` | eating |
| allergy | `allergens` | `LocalFlorist` | pollen / flower |
| skin-wound | `bandage` | `Healing` | plaster |
| eyes | `eye` | `Visibility` | eye |
| muscles-joints | `figure.walk` | `DirectionsWalk` | walking figure |
| mouth-teeth | `mouth` | `SentimentSatisfied` | mouth / smile |
| vitamins-minerals | `pills` | `Medication` | pills |
| first-aid | `cross.case` | `MedicalServices` | first-aid case |
| other | `square.grid.2x2` | `Category` | assorted |
| uncategorized | `tray` | `Inbox` | tray |

Icons are decorative (G3). A test on each platform checks that every `CategoryID` in the taxonomy has an icon. On
iOS the test also checks that `UIImage(systemName:)` exists on the minimum OS.

## 6. Status colours (pills)

The same values on both platforms. Text and icon use the foreground colour.

| Status | Light bg / fg | Dark bg / fg | Icon |
|--------|---------------|--------------|------|
| expired | `#FCEBEB` / `#791F1F` | `#501313` / `#F7C1C1` | iOS `xmark.octagon.fill`, Android `EventBusy` (existing) |
| expiringSoon | `#FAEEDA` / `#633806` | `#412402` / `#FAC775` | iOS `exclamationmark.triangle.fill`, Android `Schedule` (existing) |
| unknown | neutral pill + 1 pt/dp outline `secondaryLabel` / `outline` | same tokens | iOS `questionmark.circle.fill`, Android `QuestionMark` (existing) |
| ok | neutral: iOS `tertiarySystemFill` / `label`; Android `surfaceContainerHighest` / `onSurface` | system | none |

All four foreground/background pairs are above 7:1, well over the 4.5:1 required. A unit test on each platform
computes the WCAG contrast of every pair in both modes and fails below 4.5:1. The test resolves the iOS asset colours
for light and dark trait collections, and the Android colours directly. *Unknown* is told apart from *ok* by its icon
and outline, not by colour.

## 7. i18n

New keys (fr / en), with the same key and wording on both platforms (Android key = iOS key with `.` → `_`):

| Key | fr | en |
|-----|----|----|
| `cabinet.filter.button` | Filtres | Filters |
| `cabinet.filter.activeCount` (plural) | %d filtre actif / %d filtres actifs | %d active filter / %d active filters |
| `cabinet.filter.remove` | Retirer le filtre %@ | Remove filter %@ |
| `cabinet.filter.sectionLocation` | Emplacement | Location |
| `cabinet.filter.sectionExpiry` | Péremption | Expiry |
| `cabinet.pill.count` | ×%d | ×%d |
| `cabinet.pill.label` | %1$@, %2$@ | %1$@, %2$@ |
| `cabinet.pill.labelStatus` | %1$@, %2$@, %3$@ | %1$@, %2$@, %3$@ |

`%2$@` is the existing `cabinet.row.boxes` plural ("3 boîtes"), and `%3$@` the existing `expiry.status.*` text.
**Removed**: `cabinet.filter.active` ("Filtres : %@"). `cabinet.filter.location` and `cabinet.filter.status`, the old
menu labels, are removed if no longer used. `cabinet.filter.clear` is reused as the menu item, with its wording
checked against "Effacer les filtres". `check-string-parity.py` and both string checks must pass.

## 8. Sequence

1. The repository emits boxes. The VM calls `CabinetRows.build(boxes, filter, categoryOrder)` off the main thread,
   with `categoryOrder` from `CategoryOrder.alphabetical(labels)`.
2. `build` groups, filters and searches as before, computes each row's `displayName` per section (with the collision
   rule) and orders rows by `displayName`.
3. The view renders the header (count from `snapshot.boxCount`) and one lazily built card per section, with the pills
   in snapshot order.
4. A tap on a filter menu item calls `selectLocation` / `selectStatus` (existing), which triggers a rebuild, after
   which a chip appears. A tap on a chip's × calls `clearLocation` / `clearStatus`.
5. A tap on a pill pushes the product's boxes or the box detail (unchanged).

## 9. Performance

The scan path, persistence and import/export are untouched. `build` adds one `ShortName.of` per row and one
dictionary of normalized short names per section, which is O(rows). The existing **1 000-box build test (≤ 100 ms)**
on both platforms covers it, and the fixture-driven rows stay identical. Lists are lazy per card. A category with
hundreds of pills is measured as one card, which is acceptable: 1 000 boxes in one category means a few hundred pills,
and `FlowLayout` and `FlowRow` are O(n). Each platform gets a UI performance smoke check, a 1 000-box cabinet in one
category rendered and scrolled in a UI test (iOS `measure` with a generous budget, Android a Robolectric test that
composes it without error).

## 10. Privacy

No new data, permission, network or log. The search field keeps no history: autocorrection and suggestions are off
on iOS, and `KeyboardOptions(autoCorrectEnabled = false)` is set on Android.

## 11. Data

No schema change, no migration, no new setting and no export `formatVersion` change. `AppEnvironment.categoryOrder` is
an in-memory injection point. It is replaced (iOS: `categoryLabel` closure; Android: removed) and updated in tests.

## 12. Test strategy

| Requirement | Test |
|-------------|------|
| N1, N2 | `short-names.json` run by `ShortNameTests` (Swift) / `ShortNameTest` (Kotlin); collision cases in `cabinet-rows.json` |
| N3 | Box detail and export tests unchanged and still passing (names are never rewritten); `CabinetRows` test: `row.name` is still the full name |
| N4, K7 | iOS UI test reads the pill's `accessibilityLabel` (full name, count, status); Android Robolectric `onNodeWithContentDescription(full…)` |
| G1 | iOS and Android UI tests: a card shows label and box total; filtered totals |
| G2 | `category-order.json` (real fr/en labels) on both cores; iOS app test resolves the fr and en bundles → expected order; Android Robolectric with `@Config(qualifiers = "en")` and default fr |
| G3 | `CategoryIcon` test: every taxonomy id mapped, symbol exists (iOS); `CategoryIcons` test (Android) |
| G4 | iOS UI test: card header has the header trait; Android: `SemanticsMatcher.keyIsDefined(Heading)` |
| G5, perf | Existing 1 000-box build tests; new 1 000-box single-category render/scroll test on both |
| K1, K2, K5 | UI tests: pill shows short name and `×N`, no date, no *OK* text anywhere in the list |
| K3 | `cabinet-rows.json` new/updated ordering cases |
| K4, K6 | UI tests: expired, expiring and unknown pills carry their icon/status label; a row with an unknown box among `ok` boxes is *unknown* (fixture + UI); contrast unit test (§6) |
| K8 | `FlowLayout` unit tests (wrap, width cap); UI test with a 115-character name: count visible (iOS `XCUIElement` frame within card; Android `assertIsDisplayed` on the count node) |
| K9 | Android `assertTouchHeightIsAtLeast(48.dp)` / width; iOS UI test: pill frame ≥ 44 pt high |
| K10 | Existing navigation UI tests, updated to tap pills |
| K11 | iOS UI test launched with `-UIPreferredContentSizeCategoryName UICTContentSizeCategoryAccessibilityXXXL`: pills hittable, count visible; Android Robolectric `@Config(fontScale = 2f)` |
| F1, F2, F5 | UI tests: search field and filter button present, old filter bar identifiers gone, count next to title |
| F3 | UI tests: open menu, pick a location, pick a status, *Clear filters* |
| F4 | UI tests: active filter → chip shown, button value "1 filtre actif"; chip × clears only that filter; VM unit tests `clearLocation` / `clearStatus` / `activeFilterCount` |
| F6 | Existing search and no-results tests, adapted to cards and pills |
| X1 | `ios-check-strings.sh`, Android string checks, `check-string-parity.py` |
| X2 | Existing loading/error/empty tests, unchanged |
| X3 | `android-check-architecture.py`, lint privacy rules; no manifest/privacy-manifest diff |
| Fixtures break | `test-shared-fixtures-break.sh` with the two new fixtures |

Coverage: the new core code targets 100 % of branches. The new app code targets 90 %. The 80 % gates must hold.

## 13. Docs and version

- `docs/cabinet.md` sections *What the user sees* 1–5 are rewritten for cards, pills, short names, the new orders and
  the filter menu. The rules table gains `short-names.json` and `category-order.json`.
- `fixtures/README.md` gets the two new rows.
- Both apps get a **minor** bump on the last commit, from whatever `develop` holds at that point (0.4.0 today →
  0.5.0), with a changelog entry for each.

## 14. Risks and alternatives considered

- **Custom search field on iOS** instead of `.searchable`. Native search was rejected because it cannot put the
  filter button on the search row (§3.2). Risk: small behaviour differences from the system search bar, covered by
  UI tests.
- **Visual pill (32 pt/dp) smaller than its hit area (44/48)**, the Material chip pattern. Neighbouring hit areas
  meet without overlapping because the line spacing comes from the hit area, not the visual.
- **Code-point A–Z instead of locale collation**: a deliberate choice so both platforms produce the same order,
  pinned by a fixture. It is correct for the current labels. A future label starting with a digit or a symbol would
  sort before letters, and the fixture would catch that.
- **Full name on collision** can make one pill long. That is acceptable, since it is rare and wraps to two lines.
