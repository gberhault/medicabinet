# Shared fixtures

Language-neutral test vectors loaded by **both** the Swift (`ios/`) and the Kotlin
(`android/`) test suites. They are how the two native implementations are kept behaviourally
identical: a case added here runs on both platforms with no code change, and a case that one
platform fails fails that platform's gate.

Every file is JSON with:

| Key | Meaning |
|-----|---------|
| `formatVersion` | integer, bumped when the *shape* of the file changes |
| `description` | what the cases mean, in one or two sentences |
| `cases` | list of objects with a unique `name` (files that are not case lists, such as `schema-v1.json`, have their own keys instead) |

`python3 -I scripts/check-fixtures.py` validates the `.json` files (run by `scripts/local-ci.sh`); `.sql` files are plain text.

## Files

| File | Used for |
|------|----------|
| `expiry-dates.json` | `ExpiryDate` validation, `lastValidDay`, `sortKey`, error kinds |
| `categories.json` | the category taxonomy in display order; which categories the reference-data build may assign and which the user may pick |
| `schema-v1.sql` | the exact v1 DDL both platforms run (each platform's test compares its own copy, ignoring whitespace and `--` comments) |
| `schema-v1.json` | the tables, columns and indexes every platform's database v1 must have |
| `repository-contract.json` | names of the repository contract checks both platforms run (the checks themselves are code) |

## Names that cross platforms

These strings are identical on both platforms (storage values, error kinds, export keys):

| Concept | Values |
|---------|--------|
| Expiry error kinds | `invalidYear`, `invalidMonth`, `invalidDay` |
| `RemovalReason` | `usedUp`, `thrownAway` |
| `ExpiryStatus` | `expired`, `expiringSoon`, `ok`, `unknown` |
| `CategoryID` (taxonomy, display order) | `pain-fever`, `cold-ent`, `digestion`, `allergy`, `skin-wound`, `eyes`, `muscles-joints`, `mouth-teeth`, `vitamins-minerals`, `first-aid`, `other`, `uncategorized` |
| Repository errors | `notFound`, `unknownLocation`, `duplicate`, `duplicateDefault` |

## Adding a fixture

1. Add the JSON file with `formatVersion`, `description`, unique case names.
2. Load it in both suites through `SharedFixtures` (Swift: `MediCabinetCoreTestSupport`;
   Kotlin: `:core` test fixtures).
3. Document it in the table above.
