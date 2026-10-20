# Cabinet grid — Tasks

Status: **Approved 2026-10-20**
Requirements: [`requirements.md`](requirements.md) · Design: [`design.md`](design.md) · Issue: #48

One task = one commit (code + tests + this file ticked). Every commit builds and passes the unit tests of each
product it touches. Core rules come first and land on **both** cores in the same commit, because a shared fixture
change would otherwise break the other platform's suite.

## Shared rules (both cores)

- [x] **T1** `[shared]` `[ios]` `[android]` **Short names.** Add `fixtures/short-names.json` (the cases in design §2.4)
  and its row in `fixtures/README.md`. Add `ShortName.of` to `MediCabinetCore` and Kotlin `:core`, with
  `ShortNameTests` / `ShortNameTest` running every fixture case through `SharedFixtures`. Run
  `check-fixtures.py`, `swift test` and `:core:test`. *(N1, N2)*
- [x] **T2** `[shared]` `[ios]` `[android]` **Category order.** Add `fixtures/category-order.json` (the real fr and
  en labels plus the edge cases) and its README row. Add `CategoryOrder.alphabetical(labels:)` to both cores, with
  fixture-driven tests. *(G2)*
- [x] **T3** `[shared]` `[ios]` `[android]` **Rows: displayed name and A–Z order.**
  - Update `fixtures/cabinet-rows.json`: the description, `displayName` on every expected row (added by a one-off
    script and reviewed by hand), the rewritten ordering and month-precision cases, and the new collision cases
    (design §2.4).
  - Add `displayName` to `ProductRow` and to both fixture-support types (`CabinetFixtures.swift`, Kotlin
    `testFixtures`), and switch `rowOrder` in both cores.
  - Fix any app test that relied on the old urgency order, in the same commit.
  - Run the full unit suites of both apps. *(N1, N2, K3)*
- [ ] **T4** `[shared]` Extend `scripts/tests/test-shared-fixtures-break.sh` with a broken `short-names` case and a
  broken `category-order` case. Both suites must fail on them. *(fixture contract)*

## iOS

- [ ] **T5** `[ios]` Add `CategoryIcon` (CategoryID → SF Symbol, design §5) with a test: every taxonomy id is mapped,
  every symbol resolves with `UIImage(systemName:)`, and no two ids share a symbol. *(G3)*
- [ ] **T6** `[ios]` Add the pill status colours to `Assets.xcassets` (light and dark, design §6) and `PillStyle`
  (background, foreground, outline and icon per status). Add a contrast unit test that resolves each pair in light
  and dark trait collections and requires ≥ 4.5:1. *(K4, K5)*
- [ ] **T7** `[ios]` Add `FlowLayout` (`Features/Common/`) with unit tests on its placement function: it wraps, caps a
  subview at the full width, keeps spacing and handles empty input. *(K2, K8)*
- [ ] **T8** `[ios]` **ViewModel.**
  - Replace `AppEnvironment.categoryOrder` with a `categoryLabel` closure, and have the VM build the order with
    `CategoryOrder.alphabetical`.
  - Add `clearLocation()`, `clearStatus()` and `activeFilterCount`, and remove `activeFilterParts`.
  - Unit tests: the VM order uses the injected labels; the fr and en bundle labels give the order in the fixture; the
    single-filter clear methods; `activeFilterCount`. *(G2, F4)*
- [ ] **T9** `[ios]` **Header.**
  - Add `CabinetHeader`: the title and count, the custom search field, the filter `Menu` (Location and Expiry
    sections, *Clear filters*), and the removable chips.
  - Hide the root navigation bar, and delete `CabinetFilterBar.swift`.
  - Strings (fr + en): `cabinet.filter.button`, `.activeCount`, `.remove`, `.sectionLocation`, `.sectionExpiry`.
    Remove `cabinet.filter.active` and any other unused key.
  - UI tests: the search field and filter button sit on one row; the count sits next to the title; picking a
    location and a status from the menu; the chip appears and its × clears only that filter; the button's
    accessibility value; *Clear filters*; search still filters. *(F1–F6, X1)*
- [ ] **T10** `[ios]` **Cards and pills.**
  - Add `CategoryCardView` and `ProductPill`, and rewrite `CabinetView`'s list as a `ScrollView` with a
    `LazyVStack` of cards. Remove `ProductRowView`.
  - Strings: `cabinet.pill.count`, `cabinet.pill.label`, `cabinet.pill.labelStatus`.
  - Update `CabinetUITests` to the new layout, and add tests for:
    - a card's label, box total and header trait;
    - the pill's short name and `×N`, with no date and no *OK* in the list;
    - the expired, expiring and unknown pills: icon and accessibility label with the full name;
    - a 115-character name keeping its count visible;
    - the pill frame being ≥ 44 pt high;
    - navigation with one box and with several boxes;
    - fr category order end to end. *(G1, G4, N4, K1–K10)*
- [ ] **T11** `[ios]` Accessibility and performance UI tests:
  - At `AccessibilityXXXL`, pills stay hittable and the count stays visible. *(K11)*
  - A cabinet of 1 000 boxes in one category renders and scrolls (`measure`, a budget ≥ 3× the measured cost,
    several runs). *(G5)*

## Android

- [ ] **T12** `[android]` Add `CategoryIcons` (CategoryID → `Icons.Outlined.*`, design §5) with a test: every
  taxonomy id is mapped and no two ids share an icon. *(G3)*
- [ ] **T13** `[android]` Add `PillColors` (light/dark, design §6) with a contrast unit test requiring ≥ 4.5:1 for
  every pair. *(K4, K5)*
- [ ] **T14** `[android]` **ViewModel.**
  - Add `setCategoryOrder(order)`; until it is first called, the order is `Taxonomy.ordered`.
  - Remove `AppEnvironment.categoryOrder`.
  - Add `clearLocation()`, `clearStatus()` and `activeFilterCount`.
  - Turbine/unit tests for each. *(G2, F4)*
- [ ] **T15** `[android]` **Header.**
  - Add `CabinetHeader`: the search field, the filter `IconButton` in a `BadgedBox`, a `DropdownMenu` with its
    sections and *Clear filters*, and `InputChip`s.
  - Put the title and count in the `TopAppBar`, and delete `CabinetFilterBar.kt`.
  - Strings (`values` + `values-en`): the same keys and wording as iOS T9. Remove the unused keys.
  - Robolectric tests mirroring iOS T9. Run `check-string-parity.py`. *(F1–F6, X1)*
- [ ] **T16** `[android]` **Cards and pills.**
  - Add `CategoryCard` (`FlowRow`) and `ProductPill`, and turn `CabinetScreen`'s `LazyColumn` into one item per
    card. Resolve the labels in the composable and call `setCategoryOrder`.
  - Pill strings, as in iOS T10.
  - Update `CabinetScreenTest` / `CabinetNavigationTest`, and add Robolectric tests mirroring iOS T10, plus:
    - `assertTouchHeightIsAtLeast(48.dp)`;
    - fr (default) and `@Config(qualifiers = "en")` category order;
    - `@Config(fontScale = 2f)`;
    - a 1 000-box single-category composition. *(G1–G5, N4, K1–K11)*

## Finish

- [ ] **T17** `[shared]` Docs: rewrite "What the user sees" in `docs/cabinet.md` (cards, pills, short names, orders,
  filter menu), and add the two fixtures to its rules table. Check the `fixtures/README.md` rows. *(X4)*
- [ ] **T18** `[ios]` `[android]` Bump the minor version of both apps (`project.yml` and `build.gradle.kts`, with the
  build numbers incremented) and add the changelog entries. Run `check-version.sh ios` and `check-version.sh android`.
  *(X5)*
- [ ] **T19** `[shared]` Run `bash scripts/local-ci.sh` (shared, iOS and Android gates) and fix every failure. Record
  the coverage of both cores and both apps for the PR body.
- [ ] **T20** `[ios]` `[android]` Visual check: screenshots of the Cabinet tab on an iPhone simulator and on
  `emulator-5554` (light, dark, largest text, filter menu open) for the PR. No install on a device the user did not
  name.
