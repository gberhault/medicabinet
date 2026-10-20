# MediCabinet documentation

| Page | What it covers |
|------|----------------|
| [architecture.md](architecture.md) | The two native apps, the shared contract (fixtures), layers, the database, migrations, backups, launch sequence |
| [bulk-scan.md](bulk-scan.md) | Bulk scan intake: what the Scan tab does, the rules behind it, privacy, how to test it |
| [development.md](development.md) | Setup and commands for iOS and Android, quality gates, localization, versioning |
| [reference-data.md](reference-data.md) | BDPM dataset: licence and attribution, file format and encodings, coverage limits, classification source |
| [cabinet.md](cabinet.md) | The Cabinet tab: list by category, search and filters, a product's boxes, box detail, category editing, taking a box out with Undo |
| [expiry-tracking.md](expiry-tracking.md) | Expiry status of a box, the "expiring soon" window, the Expiring tab, the monthly reminder on each platform, privacy |
| [export-import.md](export-import.md) | Exporting the cabinet, importing it (replace only, preview and confirmation), opening a file with the app, automatic backups and restore, privacy |
| [export-format.md](export-format.md) | The `.medicabinet` archive: format version 1 field by field, canonical form, limits, error kinds, how a version is added |
| [named-products.md](named-products.md) | The products you named (a name and category remembered per code the medicines database does not know), Settings → Products you named: rename, category, forget with Undo, the upgrade fill, export |
| [locations.md](locations.md) | Managing locations (add, rename, reorder, default, delete with move and Undo), the tombstone, where the code is |
| [app-icon.md](app-icon.md) | The app icon: one source SVG generated into the Android adaptive icon, the iOS light/dark/tinted icons and the Google Play assets; how to change it, rights rules, Play requirements |

Feature pages are added next to these as each feature ships (see `specs/` for the specs that drive
them; every epic is specified once and built on both platforms).
