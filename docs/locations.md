# Locations

A *location* is a place where the household keeps medicines: bathroom, kitchen, car, travel kit… Every box
lives in exactly one location. The list is managed in **Settings → Locations** on both apps (iOS and Android
behave the same and say the same thing; the idiom differs: swipe actions and a sheet on iOS, a row menu,
dialogs and a drag handle on Android).

Specification: [`specs/cabinet-inventory/`](../specs/cabinet-inventory/requirements.md) (L1–L11).

## What you can do

| Action | Behaviour |
|--------|-----------|
| **Add** | Name of 1–50 characters (counted in Unicode code points, after trimming). The new location goes last. |
| **Rename** | Same rules. Changing only the case or accents of its own name is allowed. Its boxes stay where they are. |
| **Reorder** | Drag the handle (Android: long press, then drag; iOS: Edit, then drag). Every picker uses this order. Accessible alternative: the *Move up* / *Move down* actions of each row (VoiceOver rotor, TalkBack actions). |
| **Make default** | Exactly one location is the default; the scanner pre-selects it and it is the destination suggested when a location is deleted. |
| **Delete** | See below. |

### Names

Two locations cannot have the same name once accents, case and spacing are ignored ("Cuisine", "cuisine "
and "Cuísine" are one name). The comparison is the shared `TextNormalizer`
(`fixtures/text-normalization.json`); the rules are the shared `LocationName`
(`fixtures/location-names.json`). A refused name keeps the sheet or dialog open and says why, with text and
an icon (never colour alone).

### Deleting a location

- The **default location cannot be deleted**: its delete action is absent and the screen explains that another
  default must be chosen first. There is always at least one location.
- An **empty** location is deleted at once and a message with **Undo** appears (10 s on iOS, Material
  long duration on Android). Undo restores it in the same place; it fails with a message if its name was
  taken meanwhile.
- A location that **holds boxes** asks for confirmation: it says how many boxes it holds and where to move
  them (the default location is pre-selected). **Move and delete** moves those boxes and deletes the
  location in one transaction, so a failure leaves everything unchanged. Cancel changes nothing. This path is
  confirmed rather than undoable.
- Boxes that were removed (used up / thrown away) or deleted keep pointing at the deleted location, so
  their history is not rewritten.

A deleted location is a **tombstone** (`location.deletedAt`, schema v3): the row stays in the database and
in future exports, and is invisible everywhere in the app. Its name is free for a new location.

## Where the code is

| | iOS | Android |
|--|-----|---------|
| Rules (pure) | `MediCabinetCore/Cabinet/{TextNormalizer,LocationName}.swift` | `:core` `cabinet/{TextNormalizer,LocationName}.kt` |
| Repository port | `LocationRepository` (create, rename, reorder, setDefault, cabinetBoxCount, deleteLocation, undoDeleteLocation, observeAll) | `LocationRepository` = `LocationEditing` + reads |
| Store | `Persistence/GRDBLocationRepository.swift` | `persistence/{SqlLocationRepository,SqlLocationEditing,LocationSql}.kt` |
| Screen | `Features/Settings/Locations/` | `features/settings/locations/` |
| Undo | `Features/Common/{UndoCenter,UndoBanner}.swift` (banner above the tab bar) | `features/common/UndoController.kt` (snackbar in `MainScaffold`) |

The list follows the repository's observation (`observeAll()`), so a change made anywhere (another screen, a
future import) shows up without a refresh.

## Privacy

Location names are household data: they are never logged (failures log the failure kind or exception class
only) and never leave the device except in an export the user starts.
