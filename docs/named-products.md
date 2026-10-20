# Products you named

When a scanned code is not in the bundled medicines database ([`reference-data.md`](reference-data.md)) — parapharmacy,
supplements, devices, foreign products — the household types a name once and the app reuses it on every later scan
of that code. That name (and the category the household chose) is a **named product**, kept in its own table and
managed in **Settings → Products you named** on both apps (same behaviour and wording; the idiom differs: swipe
actions, a sheet and a confirmation dialog on iOS, a row menu and dialogs on Android).

Specification: [`specs/named-products/`](../specs/named-products/requirements.md) (issue #57).

## What is remembered, and when

- One named product per code (GTIN), only for a code the database does **not** name. For a code the database knows,
  its name always wins, as before.
- A name is **remembered when a box is saved with a name or category the household gave**: a scan completed by
  the user, *Add without scanning* with a code, a correction in the scan session list, an edit of a box (name,
  category, *Automatic category*). The last name typed wins.
- Saving a box **without** changing its name or category (expiry, location, *Opened*, take out, put back, undo)
  never creates or updates a named product, so it cannot bring a forgotten one back.
- Nothing is remembered while the reference data is unavailable (the app cannot tell a known code from an unknown
  one), for a box with no code, or for a code the database names.
- A scan of a code resolves its name from the database, else from the named product, else it asks. **A previous box
  no longer names a code**: before this feature the app reused the newest box with the code, including boxes
  already used up or thrown away, so a wrong name could not be fixed once its box had left the cabinet.

## Settings → Products you named

| Action | Behaviour |
|--------|-----------|
| **List** | Every named product by name (accents and case ignored): name, code, category, and *N boxes in the cabinet* or *Not in the cabinet*. An empty state explains when products appear. |
| **Edit** | Name (1–200 characters after trimming) and category (the user-selectable categories, or *Automatic category* = *Uncategorized*, since the database does not know the code). |
| **Save a new name** | The product **and every box with that code** that is not deleted — in the cabinet, used up or thrown away — take the new name, in one transaction. |
| **Save a new category** | The same: the product and all those boxes take the category, marked as set by the user (*Automatic* clears the mark). |
| **Forget** | Confirmation with the product's name, and how many boxes are still in the cabinet (they keep their name). The next scan of the code asks for a name. A message with **Undo** follows; Undo says it cannot restore when the code was named again meanwhile. |

A box added by mistake (a tombstone) keeps its name. Categories are for tidying the cabinet, not for choosing a
treatment.

## After the upgrade

Schema v4 (`namedProduct`, `cabinetMeta`; see [`architecture.md`](architecture.md)) cannot be filled by the
migration: deciding which codes the database names needs the reference data, a separate file. The migration sets a
`pending` marker; after launch, in the background, the app creates one named product for every code that has a box
(in the cabinet or taken out, not deleted) and that the database does not name, from the newest such box (name, and
category with its flag when the user set it). It runs once, never on the cold-start path, and waits for a launch where
the reference data is available. The marker lives in the database, so a restored backup or an import of an older
archive brings the pending fill back with it. A scan in the first moments after the upgrade can ask for a name the
fill was about to provide; the name typed then wins.

## Export and import

The archive carries the named products (see [`export-format.md`](export-format.md): `formatVersion` 2). Version 1
files still import; their named products are filled from their boxes, as after an upgrade. Automatic backups are
archives, so they carry the products too.

## Where the code is

| | iOS | Android |
|--|-----|---------|
| Rules (pure) | `MediCabinetCore/Cabinet/` and `Scan/`: `NamedProduct`, `ProductResolver`, `MemoryPolicy`, `ProductMemory`, `NamedProductEdit`, `NamedProductBackfill(Runner)` | the same names in `:core` |
| Shared fixtures | `product-resolution.json`, `product-memory.json`, `named-product-backfill.json`, `repository-contract.json` | the same files |
| Repository port | `NamedProductRepository` | `NamedProductEditing` + `NamedProductRepository` |
| Store | `Persistence/GRDBNamedProductRepository.swift` | `persistence/SqlNamedProductRepository.kt` |
| Screens | `Features/Settings/NamedProducts/` | `features/settings/namedproducts/` |

## Privacy

Named products are household health data: product names are never logged (failures log the failure kind or exception
class only), they stay on the device and leave it only in an export the user starts.
