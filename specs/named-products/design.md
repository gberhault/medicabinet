# Products you named — Design

Status: **Pre-approved 2026-10-20** (implementation waits for the user's go)
Requirements: [`requirements.md`](requirements.md) · Issue: #57

## 1. Overview and dependency

| Piece | Where | Requirements |
|-------|-------|--------------|
| `namedProduct` table + `cabinetMeta` marker, schema v4 | both persistence layers, `fixtures/schema-v4.*` | N1, X1 |
| `NamedProduct` model, `NamedProductRepository` port, fakes, contract | both cores | N1, E3, E4, F2, F3 |
| `ProductResolver` takes the named product instead of the newest box for unknown codes | both cores | N3 |
| `ProductMemory` use case + `MemoryPolicy` rule: when a save remembers | both cores | N2, N4–N8 |
| `NamedProductBackfill` rule + runner | both cores, bootstrap | B1–B4 |
| Archive `formatVersion` 2 | both cores, archive stores | A1–A5 |
| Settings → Products you named | both apps | S, E, F |

**Base branch.** The archive-store and import changes (§7) build on the export-import epic (#26, PR #55), which is
not on `develop` yet. Implementation starts after PR #55 merges, with this branch rebased onto `origin/develop`.
Version 1 of the archive stays readable forever (§7).

## 2. Data

### 2.1 Schema v4 (`fixtures/schema-v4.sql`, identical on both platforms)

```sql
CREATE TABLE namedProduct (
  id                 TEXT PRIMARY KEY NOT NULL,
  gtin               TEXT NOT NULL CHECK (length(gtin) = 14),
  name               TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  category           TEXT NOT NULL DEFAULT 'uncategorized',
  categoryOverridden INTEGER NOT NULL DEFAULT 0,
  createdAt          DATETIME NOT NULL,
  updatedAt          DATETIME NOT NULL,
  deletedAt          DATETIME
);
CREATE UNIQUE INDEX namedProduct_gtin ON namedProduct(gtin) WHERE deletedAt IS NULL;
CREATE TABLE cabinetMeta (
  key   TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL
) WITHOUT ROWID;
INSERT INTO cabinetMeta (key, value) VALUES ('namedProductBackfill', 'pending');
```

- The `box` and `location` columns are unchanged. Box names are copied into named products, never linked by a
  foreign key: a box keeps its own name (F2), and a forgotten product leaves its boxes alone.
- The partial unique index enforces "one live named product per GTIN" (N1), and lets a forgotten row and a new one
  coexist (F3, F4). It also serves the lookup by GTIN.
- `cabinetMeta` holds the **backfill marker** (§5). The migration cannot fill the table itself: deciding whether a
  code is known needs the reference database, which is a separate file. The marker is inside the cabinet database,
  so a restored pre-migration backup, or a v1 import, brings the pending fill back with it. A preference stored
  outside the database would not. The table is generic, but only this key is defined.
- No semicolon inside a literal or comment, because the Android `Migration` splits on `;`.
- `fixtures/schema-v4.json` is the v3 snapshot plus the two tables and the index. `SchemaFixtureTests.swift`
  (`theFixturesCoverBothTablesAndEveryLookupIndex`) and `SchemaTest.kt` (`theFixtureCoversBothTablesAndEveryLookupIndex`)
  assert the table set, so they change to cover `namedProduct` and `cabinetMeta`.

### 2.2 Core model (twins)

```swift
public struct NamedProduct: Identifiable, Hashable, Sendable {
    public let id: UUID
    public let gtin: String
    public var name: String
    public var category: CategoryID
    public var categoryOverridden: Bool
    public let createdAt: Date
    public var updatedAt: Date
}

public struct NamedProductSummary: Hashable, Sendable {   // one list row
    public let product: NamedProduct
    public let boxesInCabinet: Int
}
```

Kotlin: `data class NamedProduct(val id: UUID, val gtin: String, val name: String, val category: CategoryID, val
categoryOverridden: Boolean, val createdAt: Instant, val updatedAt: Instant)` and `NamedProductSummary`. As with
`Location`, `deletedAt` exists only on the record and archive types (`ArchivedNamedProduct`).

### 2.3 Port (`Repositories.swift` / `Repositories.kt`)

```swift
public protocol NamedProductRepository: Sendable {
    /// The live named product of a GTIN.
    func namedProduct(gtin: String) async throws -> NamedProduct?
    /// Creates or updates the live named product of `gtin`; no write when name, category and flag are equal.
    func remember(gtin: String, name: String, category: CategoryID, categoryOverridden: Bool) async throws
    /// Live named products with their in-cabinet box counts, one query, re-emitted on every change of either table.
    func observeSummaries() -> AsyncThrowingStream<[NamedProductSummary], any Error>
    /// Renames the product and every non-deleted box with its GTIN, in one transaction (E3).
    func rename(id: NamedProduct.ID, to name: String) async throws
    /// Sets the category of the product and of every non-deleted box with its GTIN, in one transaction (E4).
    func setCategory(id: NamedProduct.ID, _ category: CategoryID, overridden: Bool) async throws
    func forget(id: NamedProduct.ID) async throws
    /// Throws `RepositoryError.duplicate` when another live named product holds the GTIN (F3).
    func undoForget(id: NamedProduct.ID) async throws
    // Backfill (§5)
    func backfillPending() async throws -> Bool
    func completeBackfill(_ products: [NamedProduct]) async throws   // inserts + clears the marker, one transaction
}
```

- Kotlin splits it into `NamedProductEditing` and `NamedProductRepository`, as `LocationEditing` /
  `LocationRepository` are split, because of the detekt per-interface function limit.
- Every write stamps `updatedAt` from the injected `Clock`. Rename and category writes stamp the boxes they change
  too.
- `rename` and `setCategory` validate nothing themselves. The name rule lives in the core (`NamedProductEdit`,
  §3.3), as `BoxEdit` does for boxes.
- `BoxRepository` gains `latestBoxPerGTIN() -> [Box]`: the newest non-deleted box of every GTIN, ordered by
  `createdAt DESC, id DESC`, in one query (`GROUP BY` with a window function, or a correlated `MAX`), for the backfill.
  `latestBox(gtin:)` stays: it still carries a user-set category over for known codes.
- Implementations: `GRDBNamedProductRepository.swift`, `SqlNamedProductRepository.kt`, and the in-memory fakes
  (`InMemoryCabinetStore` on both platforms implements the new port).
- `RepositoryContract` gains a named-product section, with the checks listed in `fixtures/repository-contract.json`
  (§9): remember creates; remember with equal values does not write (updatedAt unchanged); remember updates;
  one live product per GTIN; rename renames in-cabinet and removed boxes but not deleted ones; setCategory likewise;
  forget hides from summaries and keeps boxes; undoForget restores; undoForget refuses when the GTIN was named again;
  summaries count only in-cabinet boxes and order by normalized name then GTIN; observeSummaries re-emits on a box
  change; completeBackfill clears the pending flag; `latestBoxPerGTIN` returns one box per GTIN.

## 3. Core rules

### 3.1 `ProductResolver` (changed)

```swift
public static func resolve(reference: ReferenceProduct?, named: NamedProduct?, previousBox: Box?) -> ResolvedProduct
```

- **Reference known** (`reference != nil`): unchanged. The name is the reference name. The category is the previous
  box's when the user set it, else the reference category, else `uncategorized`. `named` is ignored.
- **Reference unknown**: the name is `named?.name` (or `nil`: ask). The category is `named.category` with the flag
  on when `named.categoryOverridden`, else `uncategorized` with the flag off. `previousBox` is ignored (N3).

`fixtures/product-resolution.json` gains a `named` input on every case. The cases that relied on the previous box
for an unknown code are rewritten to expect `nil` / `uncategorized`. New cases: named only; named with a user
category; named and previous box disagree (named wins); reference and named both present (reference wins); named
with flag off and a non-default category stored (gives `uncategorized`).

### 3.2 `MemoryPolicy` (new, pure) and `ProductMemory` (new use case)

```swift
public enum MemoryEvent: Sendable { case added(Box), changed(before: Box, after: Box), sessionCorrected(before: Box, after: Box) }
public enum MemoryDecision: Equatable, Sendable { case none, remember(name: String, category: CategoryID, overridden: Bool) }

public enum MemoryPolicy {
    /// `codeKnown`: `nil` = the reference is unavailable or the lookup failed.
    public static func decide(_ event: MemoryEvent, codeKnown: Bool?, current: NamedProduct?) -> MemoryDecision
}
```

Rule (N2, N4–N7):

1. No GTIN on the box → `none`.
2. `codeKnown != false` (known, or cannot tell) → `none`.
3. `.added` → `remember(box)` unless `current` already equals it (then `none`).
4. `.changed` / `.sessionCorrected` → `none` when name, category and flag are all equal before and after (N5).
   Otherwise `remember(after)`, unless `current` already equals it.

`.sessionCorrected` is kept distinct from `.changed` only to name the source in tests. It follows the same rule.

```swift
public struct ProductMemory: Sendable {
    public init(named: any NamedProductRepository, reference: any ReferenceDataSource)
    public func resolve(gtin: String, previousBox: Box?) async throws -> ResolvedProduct   // reference → named → ask
    public func record(_ event: MemoryEvent) async throws                                  // policy → remember
}
```

- `record` asks `reference.status()` first. When it is not `.available`, `codeKnown` is `nil`. Otherwise
  `codeKnown = (try? await reference.product(gtin:)) != nil`, and a thrown lookup also gives `nil`.
- A failure of `record` never undoes the box write. The caller logs the failure kind and carries on: losing one
  memory update must not lose a box (the box is the household's data, the memory a convenience).
- `ScanIntake` takes a `ProductMemory` instead of reading `latestBox` for names. `resolve(gtin:)` calls
  `memory.resolve`. `add` calls `memory.record(.added(box))` after `boxes.insert`. `ScanIntake` stays the only
  writer of new boxes.
- `fixtures/product-memory.json` holds the `MemoryPolicy.decide` cases: each event kind × known / unknown /
  unavailable × current absent / equal / different; no GTIN; expiry-only and location-only changes;
  *Automatic category*; a name change on a box whose product was forgotten (`current` absent → remember).

### 3.3 `NamedProductEdit` (new, pure)

The same shape as `BoxEdit`: `init(from: NamedProduct)`, `name`, `chooseCategory(_:)` (only
`Taxonomy.userSelectable`; otherwise `.categoryNotSelectable`), `resetCategory()` (→ `uncategorized`, flag off),
`validated() throws(NamedProductEditError) -> (name: String?, category: (CategoryID, Bool)?)`. It returns only what
changed. Errors: `emptyName`, `nameTooLong` (200 code points after `TextNormalizer.trimmed`, the box rule). It is
covered by unit tests in both cores. No shared fixture: the name rule is already shared through
`TextNormalizer` and `ScanIntake.maxNameLength`, and the twins' tests mirror each other.

### 3.4 `NamedProductBackfill` (new, pure)

```swift
public enum NamedProductBackfill {
    /// `latest`: the newest non-deleted box of every GTIN. `isKnown`: the reference lookup.
    public static func products(latest: [Box], isKnown: (String) -> Bool, ids: () -> UUID, now: Date) -> [NamedProduct]
}
```

The rule for each box with a GTIN that `isKnown` rejects: name = box name, and category plus flag = the box's when
its flag is on, else `uncategorized` with the flag off. `createdAt = updatedAt = now`. The output is ordered by GTIN.
`fixtures/named-product-backfill.json`: an unknown code with one box; several boxes (the newest wins, and a tie on
`createdAt` is broken by id); the newest box removed (still used); the newest box deleted (the next one is used: the
repository query already excludes deleted boxes, and the fixture states the input after that filter); a known code
(skipped); no GTIN (skipped); a user category kept; a non-user category dropped.

## 4. Where `record` is called (app layer)

| Path | iOS | Android | Event |
|------|-----|---------|-------|
| Scan completed / *confirm another* / *Add without scanning* | `ScanIntake.add` (core) | `ScanIntake.add` (core) | `.added` |
| Correction in the scan session list | `ScanViewModel+Session.update` | `ScanViewModel.update` | `.sessionCorrected` |
| Box edit save (name, category, *Automatic category*) | `BoxEditViewModel.save` | `BoxDetailViewModel.save` | `.changed` |

The two app paths call `environment.productMemory.record(...)` after their `boxes.update` succeeds. `AppEnvironment`
gains `named: any NamedProductRepository` and `productMemory: ProductMemory` on both platforms, built in
`Bootstrap` from the cabinet store and `reference`.

## 5. Backfill runner

- **When.** After bootstrap succeeds, a background task (iOS `Task.detached(priority: .utility)` from the app root,
  Android a coroutine on the injected IO dispatcher in `MediCabinetApplication`) runs `NamedProductBackfillRunner.run()`.
  Nothing on the cold-start path waits for it (B2).
- **Steps.** `backfillPending()` → stop if `false`. `reference.status()` → stop if not `.available` (it retries at
  the next launch, B2). `boxes.latestBoxPerGTIN()` → for each GTIN, `reference.product(gtin:)`. Any thrown lookup
  stops the run without writing (the next launch retries). Then `NamedProductBackfill.products(...)` and
  `completeBackfill(products)`, which inserts the rows and deletes the marker in one transaction.
- **Conflicts.** A named product created between the read and the write, by a scan right after the upgrade, wins.
  `completeBackfill` skips a GTIN that already has a live named product (`INSERT … WHERE NOT EXISTS`). A scan in the
  first second after an upgrade could ask for a name the backfill was about to provide. This is accepted and listed
  in §13.
- The runner lives in the core (`NamedProductBackfillRunner`, ports only) so both platforms share its tests.
- **Import.** `CabinetArchiveStore.replace` writes the marker as `pending` when the archive's source version is 1,
  and deletes it otherwise. The app runs the runner after a successful import, the same way as after launch (B3).

## 6. Settings → Products you named

### 6.1 Strings (identical on both platforms; iOS `a.b` = Android `a_b`; **P** = plural)

| Key | fr | en |
|-----|----|----|
| `settings.namedProducts` | Produits nommés par vous | Products you named |
| `namedProducts.title` | Produits nommés par vous | Products you named |
| `namedProducts.empty.title` | Aucun produit nommé | No named product |
| `namedProducts.empty.message` | Quand vous scannez un produit que la base des médicaments ne connaît pas, le nom que vous lui donnez apparaît ici. | When you scan a product the medicines database does not know, the name you give it appears here. |
| `namedProducts.inCabinet` **P** | one: `%lld boîte dans l'armoire` · other: `%lld boîtes dans l'armoire` | one: `%lld box in the cabinet` · other: `%lld boxes in the cabinet` |
| `namedProducts.notInCabinet` | Pas dans l'armoire | Not in the cabinet |
| `namedProducts.edit` | Modifier | Edit |
| `namedProducts.edit.title` | Modifier le produit | Edit product |
| `namedProducts.edit.note` | Le nom et la catégorie s'appliquent à toutes les boîtes de ce produit, y compris celles utilisées ou jetées. | The name and category apply to every box of this product, including used-up and thrown-away ones. |
| `namedProducts.forget` | Oublier | Forget |
| `namedProducts.forget.title` | Oublier « %1$@ » ? | Forget “%1$@”? |
| `namedProducts.forget.message` | Le prochain scan de ce code vous demandera un nom. | The next scan of this code will ask you for a name. |
| `namedProducts.forget.messageInCabinet` **P** | one: `%lld boîte est encore dans l'armoire et garde son nom. Le prochain scan de ce code vous demandera un nom.` · other: `%lld boîtes sont encore dans l'armoire et gardent leur nom. Le prochain scan de ce code vous demandera un nom.` | one: `%lld box is still in the cabinet and keeps its name. The next scan of this code will ask you for a name.` · other: `%lld boxes are still in the cabinet and keep their name. The next scan of this code will ask you for a name.` |
| `namedProducts.forgotten` | « %1$@ » oublié | “%1$@” forgotten |
| `namedProducts.undoFailed` | Impossible de rétablir : ce code a été nommé à nouveau. | Cannot restore: this code has been named again. |
| `namedProducts.error.title` | Liste indisponible | List unavailable |
| `namedProducts.error.message` | Les produits nommés n'ont pas pu être lus. | The named products could not be read. |
| `namedProducts.changeFailed` | La modification n'a pas pu être enregistrée. | The change could not be saved. |

Reused, unchanged: the box-edit name problems (empty / too long), the category labels, *Automatic category*, the
category note, Save / Cancel / Retry / Undo, `box.code`. Android-only (in `ANDROID_ONLY` with a reason, as
`locations_more`): `namedProducts_more` (overflow menu content description). The exact reused keys are listed in
`tasks.md` T15 after a check of both catalogues. A missing twin is added rather than worded differently.

### 6.2 iOS (`Features/Settings/NamedProducts/`)

- `SettingsView`: a `NavigationLink { NamedProductsEntry() } label: { Label("settings.namedProducts", systemImage:
  "tag") }` after Locations, id `settings.namedProducts`. `NamedProductsEntry` reads `appEnvironment` and
  `undoCenter`, like `LocationsEntry`.
- `NamedProductsViewModel` (`@MainActor @Observable`): `Phase` (loading / loaded([NamedProductSummary]) / failed),
  `observe()` over `named.observeSummaries()`, `edit(_:)`, `save(_:)`, `requestForget(_:)`, `confirmForget()`,
  `changeFailed`. Undo: `undo?.show(forgotten message) { try await named.undoForget(id:) }`, and `undoFailed` when it
  throws `.duplicate`.
- `NamedProductsView`: a `List` with id `namedProducts.list`. Rows: name (headline), code · category (subheadline),
  count line. `.frame(minHeight: 44)`, `.accessibilityElement(children: .combine)`, and accessibility actions
  *Edit* / *Forget*. Trailing swipe: Forget (destructive), Edit. `.contextMenu` repeats them. Tapping a row opens
  Edit. Empty: `ContentUnavailableView` (id `namedProducts.empty`). Failed: `ContentUnavailableView` + Retry.
- `NamedProductEditSheet`: a `Form` with a name `TextField` (id `namedProducts.edit.name`) and the problem `Label`
  in the footer, the category `Picker` (the `BoxEditView` category section, extracted into a shared
  `CategoryPickerSection` in `Features/Common/` and used by both), and the note. Cancel / Save in the toolbar.
- Forget confirmation: `.confirmationDialog(title, titleVisibility: .visible)` with a destructive *Forget* and
  *Cancel* (the `ImportPreviewView` pattern).
- `UITestSeed` gains `namedProducts: [{gtin, name, category?}]`, used with the temporary-storage argument only.

### 6.3 Android (`features/settings/namedproducts/`)

- `SettingsScreen` gets an `onOpenNamedProducts` callback. Its `ListItem` (`Icons.Outlined.Label`, testTag
  `settings.namedProducts`) goes after Locations. `MainScaffold` adds the route `settings/namedProducts`.
- `NamedProductsRoute` → `NamedProductsViewModel(environment.named)`: `StateFlow<NamedProductsUiState>` (`Loading` /
  `Loaded` / `Failed`), with a one-shot undo offer and write outcome, following the `LocationDeletions` /
  `LocationWrites` pattern.
- `NamedProductsScreen`: `Scaffold` + `TopAppBar` (back `about_back`) + `LazyColumn` (`namedProducts.list`).
  `ListItem` rows ≥ 48 dp, a click → Edit, an overflow `IconButton` (`namedProducts.menu`) → Edit / Forget, and
  `semantics { customActions }` for both. Empty / failed through `StatusMessage`.
- `NamedProductEditDialog`: name `OutlinedTextField` with `supportingText` problem, and a category field (the
  `BoxEditSheet` `CategoryField` extracted into `features/common/CategoryField.kt` and reused). Note and Save / Cancel.
- `ForgetNamedProductDialog`: an `AlertDialog` whose confirm button uses the error colour (the
  `ReplaceConfirmation` pattern). Undo through `LocalUndoController.show(...)`, and `notify(undoFailed)` on
  `.duplicate`.

## 7. Archive format version 2

### 7.1 Shape

Version 2 = version 1 plus a root member `namedProducts` after `boxes`:

```json
"namedProducts": [
  { "id": "…", "gtin": "03401234567890", "name": "Crème solaire", "category": "skin-wound",
    "categoryOverridden": true, "createdAt": "…", "updatedAt": "…", "deletedAt": null }
]
```

- Every key is required (null explicit), and unknown or repeated keys are refused, as in v1. Order: `createdAt`,
  then `id`. The dates use the v1 date form.
- Validation (`ArchiveValidator`): unique ids (`duplicateId`), a GTIN of 14 ASCII digits, a name of 1–200 code
  points, a category of 1–100 code points (free text, as for boxes), at most one live product per GTIN (new kind
  `duplicateNamedProduct`, added to `fixtures/README.md`'s error kinds), and at most
  `ArchiveLimits.maxNamedProducts = 20 000`.
- `CabinetContents` gains `namedProducts: [ArchivedNamedProduct]`. `needsBackup` also becomes true when there is any
  named product. `CabinetSummary` gains `namedProducts` (live count), shown in the import preview as one more line
  (*Products you named: 3*). New strings: `transfer.preview.namedProducts` (fr *Produits nommés par vous :
  %lld* / en *Products you named: %lld*).

### 7.2 Readers and writer

- `ArchiveV2Reader` is appended to `ArchiveDecoder.readers`, so `currentVersion` becomes 2. `ArchiveV1Reader` is
  untouched. It returns an empty `namedProducts`, and `Archive.sourceFormatVersion = 1` tells the store to set the
  backfill marker (§5).
- `ArchiveEncoder` writes version 2 only.
- Fixtures: `fixtures/archive/v2/golden.medicabinet` (new; one live product, one forgotten, one with a user category)
  and `fixtures/archive/v1/golden.medicabinet` (unchanged). `archive-cases.json` gains v2 cases: golden; missing
  `namedProducts`; an unknown key in a product; a bad GTIN; an empty name; a name of 201; a duplicate id; two live
  products with one GTIN (refused); a live and a deleted one with one GTIN (accepted); over the limit (generated).
  The `summary` expectation gains `namedProducts`. The v1 cases' expectations gain `namedProducts: 0` and keep
  their inputs. `theEncoderReproducesTheGoldenArchiveByteForByte` targets v2.
  `everyShippedFormatVersionKeepsAGoldenArchive` covers both files.
- `archive-store-contract.json`: the snapshot includes named products, forgotten ones too; replace swaps them; a v1
  replace sets the backfill marker; a v2 replace clears it; a throwing hook leaves them unchanged.

### 7.3 Stores

`GRDBCabinetArchiveStore` / `SqlCabinetArchiveStore`: `snapshot()` reads `namedProduct` too. `replace` deletes
`namedProduct` before `box` and `location`, inserts the archive's products, and writes or deletes the marker, all
in the existing single transaction. Automatic backups are archives, so they carry the products with no further
change.

## 8. Flows

**Scan of an unknown code, first time.** Parse → `ScanIntake.evaluate` → `memory.resolve`: the reference has
nothing and there is no named product, so the name is `nil` → completion form → the user types *Crème solaire* →
`add` inserts the box → `record(.added)`: the reference is available and the code unknown, with no current product
→ `remember`.

**Same code, later.** `resolve` → the named product → name *Crème solaire*, no form for the name.

**Fixing a test name.** Settings → Products you named → the *Test* row → Edit → *Crème solaire* → Save →
`NamedProductEdit.validated` → `rename(id:to:)` (the product and 3 boxes, one of them used up, in one transaction)
→ `observeSummaries` and `observeCabinet` re-emit, and the list and the Cabinet tab update.

**Forget.** Forget → dialog (*1 box is still in the cabinet and keeps its name…*) → Forget → `forget(id:)` → the row
leaves → *“Test” forgotten · Undo*. A later scan asks for a name.

**Upgrade.** Launch → backup → migration v4 (marker `pending`) → app interactive → background runner → reference
available → `latestBoxPerGTIN` (say 120 GTINs) → 120 lookups → 14 unknown → `completeBackfill(14)` → marker cleared.

## 9. Shared fixtures summary

| File | Change |
|------|--------|
| `schema-v4.sql`, `schema-v4.json` | new |
| `product-resolution.json` | `named` input on every case; unknown-code cases rewritten; new cases (§3.1) |
| `product-memory.json` | new (§3.2) |
| `named-product-backfill.json` | new (§3.4) |
| `repository-contract.json` | named-product checks appended (§2.3) |
| `scan-intake.json` | description: "productKnown: the name is already resolved (reference data or a named product)". No case changes |
| `archive/v2/golden.medicabinet`, `archive-cases.json`, `archive-store-contract.json` | §7 |
| `README.md` | the new files, the `duplicateNamedProduct` error kind |

`scripts/tests/test-shared-fixtures-break.sh` gains broken `product-memory` and `named-product-backfill` cases.

## 10. Performance and privacy

- **Scan path.** `resolve` replaces one indexed box query (`box_gtin`) with one indexed product query
  (`namedProduct_gtin`). `record` adds, after the box is saved and the confirmation shown, one reference lookup
  (already cached by the reference actor) and at most one indexed write. The box-confirmed-on-screen time (< 300 ms)
  does not include it: the card is shown when `add` returns, and `record` runs after it. A performance test asserts
  `add` + `record` on a 1 000-box cabinet stays within budget (≥ 3× the measured cost, several runs).
- **List.** One query: `namedProduct` `LEFT JOIN` a grouped count of in-cabinet boxes by GTIN, using `box_gtin` and
  `box_active`. A test with 1 000 named products and 1 000 boxes asserts the query time.
- **Rename.** `UPDATE box … WHERE gtin = ? AND deletedAt IS NULL` (`box_gtin`).
- **Backfill.** One grouped box query plus one lookup per GTIN, in the background. A test asserts 1 000 GTINs within
  budget.
- **Privacy.** No new permission and no network. Named products are household health data: they live in the cabinet
  database (iOS Data Protection class unchanged, Android internal storage and Auto Backup as the cabinet), and go
  out only in an export. Logs carry failure kinds and exception class names only. No name or GTIN is logged
  (reviewed in every new `Log` / `AppLog` call). `PrivacyInfo.xcprivacy` is unchanged.

## 11. Test strategy

| Requirement | Tests |
|-------------|-------|
| N1, X1 | schema fixture tests (v4 snapshot and SQL equality, both platforms); `MigrationV4Tests` / `SchemaV4Test`: upgrading a v3 cabinet keeps every box and location, creates the empty table, sets the marker; step-by-step equals fresh install |
| N2, N4–N7 | `product-memory.json` through `MemoryPolicy` on both cores; `ProductMemoryTests` (status unavailable → no write; a lookup that throws → no write; a `remember` failure is reported and does not throw into intake) |
| N3 | `product-resolution.json` on both cores; `ScanIntakeTests` / `ScanIntakeTest`: `theNameOfAnEarlierBoxIsReused…` replaced by `theNamedProductNameIsReused…` and `aForgottenProductAsksForANameAgain`; a used-up box with a test name no longer names the next scan (regression of the reported problem) |
| N4 app paths | iOS `BoxEditViewModelTests`, `ScanViewModelTests` (session correction); Android `BoxDetailViewModelTest`, `ScanViewModelTest`: a name change records, an expiry-only change does not |
| B1, B4 | `named-product-backfill.json` on both cores |
| B2, B3 | `NamedProductBackfillRunnerTests` (both cores, fakes): pending + available → rows + marker cleared; unavailable → nothing, still pending; a throwing lookup → nothing; not pending → no lookup; a product created meanwhile is kept. Store tests: `completeBackfill` is one transaction, `WHERE NOT EXISTS` |
| S1–S4 | iOS `NamedProductsViewModelTests` (phases, order, counts, follows changes); `NamedProductsUITests` (Settings row, list rows, empty state, largest text size + dark mode). Android `NamedProductsViewModelTest`, `NamedProductsScreenTest` (Robolectric: rows, empty, error + retry, font scale 2, French plurals), `NamedProductsNavigationTest` |
| S5, S6 | UI tests: accessibility actions present; row height ≥ 44 pt / 48 dp |
| S7 | repository performance test (1 000 products) on both platforms |
| E1–E6 | `NamedProductEditTests` (both cores: trims, empty, 200 / 201, category not selectable, reset); repository contract (rename and setCategory scope); VM tests (save, failure → `changeFailed`, nothing changed); UI test: rename from Settings changes the name shown in the Cabinet tab |
| F1–F5 | VM tests (confirmation message with 0 / 1 / 2 boxes, cancel, confirm, undo, undo refused); repository contract (forget keeps boxes; undo duplicate); UI test: forget + Undo above the tab bar |
| A1–A5 | archive fixture suites on both cores (v1 golden still decodes, v2 golden round-trips byte for byte, every new case); archive-store contract on both stores; `CabinetTransferTests`: importing v1 sets the marker and the runner fills from the imported boxes |
| X2 | review of log calls; the existing `PrivacyTest` and lint rules |
| X4 | string checks and parity check |

## 12. Docs and version

- `docs/named-products.md` (new): what is remembered and when, the Settings list, rename / category / forget, the
  upgrade fill, where the code is, privacy. Linked from `docs/README.md`.
- `docs/bulk-scan.md`: name resolution paragraph (named product instead of the newest box for unknown codes).
- `docs/export-format.md`: "Format version 2", its field table, the new error kind; the version table.
- `docs/architecture.md`: schema v4, the two tables, the backfill marker.
- `fixtures/README.md`: §9.
- iOS and Android minor bump with changelog entries.

## 13. Risks and alternatives considered

- **Upgrade race.** A scan in the first moments after the upgrade, before the background fill finishes, asks for a
  name the fill would have provided. The name typed then wins, and nothing is lost. Waiting for the fill before
  enabling the scanner was rejected: it would put reference I/O on the start path for one launch.
- **Reference unavailable on the upgrade launch.** The fill waits for a launch where it is available. Until then,
  unknown codes ask for a name. The reference ships in the app, so this only happens if the bundle is damaged.
- **A data update starts naming a code.** The database name wins on scan (N3), and the named product stays listed.
  This is harmless and out of scope (requirements).
- **Archive version 2 before version 1 is released.** If PR #55 and this branch ship in the same release, no
  household ever has a v1-only app. v1 is kept anyway, as the format rules require.
- **Derive the list from boxes instead of a table.** Rejected: a derived list cannot forget a name while boxes keep it
  (decision 2), and cannot keep a name after its last box is deleted.
- **Fill the table in the SQL migration.** Rejected: SQL cannot tell which codes the reference knows, so known codes
  would be listed as "named by you".
- **Rename only boxes in the cabinet / future scans only.** Rejected by the user (decision 1).
