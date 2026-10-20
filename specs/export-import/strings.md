# Export and import of the cabinet — Strings

The wording of every new string, fixed before the iOS (T11–T16) and Android (T17–T22) blocks
so both apps say the same thing (`check-string-parity.py`). iOS key `a.b.c` = Android name
`a_b_c`. Placeholders are written iOS-style: `%1$@` (text) = Android `%1$s`, `%1$lld` (count)
= Android `%1$d`. **P** = plural (`one` / `other`). Dates are formatted by the platform
(`Date.FormatStyle` / `DateTimeFormatter`, date + time, medium) before being passed as `%@`.

A string needed on one platform only is added to that platform and to `IOS_ONLY` /
`ANDROID_ONLY` in `scripts/check-string-parity.py` with the reason. A wording change is made
here first, then on both platforms.

## Settings section

| Key | fr | en |
|-----|----|----|
| `transfer.section.title` | Sauvegarde et transfert | Backup and transfer |
| `transfer.section.footer` | Un fichier .medicabinet contient toute l'armoire. Il sert de sauvegarde ou à passer sur un autre téléphone, iPhone ou Android. | A .medicabinet file holds the whole cabinet. Use it as a backup or to move to another phone, iPhone or Android. |

## Export

| Key | fr | en |
|-----|----|----|
| `transfer.export.action` | Exporter l'armoire | Export the cabinet |
| `transfer.export.title` | Exporter l'armoire | Export the cabinet |
| `transfer.export.loading` | Préparation de l'export… | Preparing the export… |
| `transfer.export.summary.boxes` **P** | one: `%1$lld boîte dans l'armoire` · other: `%1$lld boîtes dans l'armoire` | one: `%1$lld box in the cabinet` · other: `%1$lld boxes in the cabinet` |
| `transfer.export.summary.removed` **P** | one: `%1$lld boîte retirée (historique)` · other: `%1$lld boîtes retirées (historique)` | one: `%1$lld removed box (history)` · other: `%1$lld removed boxes (history)` |
| `transfer.export.summary.locations` **P** | one: `%1$lld lieu` · other: `%1$lld lieux` | one: `%1$lld location` · other: `%1$lld locations` |
| `transfer.export.summary.empty` | L'armoire ne contient aucune boîte. Le fichier contiendra seulement les lieux. | The cabinet has no box. The file will hold the locations only. |
| `transfer.export.warning` | Ce fichier contient des données de santé (vos médicaments). Il n'est pas chiffré : toute personne qui l'obtient peut le lire. Ne le partagez qu'avec des personnes de confiance. | This file contains health data (your medicines). It is not encrypted: anyone who gets it can read it. Share it only with people you trust. |
| `transfer.export.continue` | Continuer | Continue |
| `transfer.export.cancel` | Annuler | Cancel |
| `transfer.export.writing` | Création du fichier… | Creating the file… |
| `transfer.export.saveToFile` | Enregistrer dans un fichier | Save to file |
| `transfer.export.share` | Partager | Share |
| `transfer.export.saved` | Fichier enregistré | File saved |
| `transfer.export.failed` | Le fichier n'a pas pu être créé. Votre armoire n'a pas été modifiée. | The file could not be created. Your cabinet was not changed. |
| `transfer.export.retry` | Réessayer | Try again |

## Import

| Key | fr | en |
|-----|----|----|
| `transfer.import.action` | Importer une armoire | Import a cabinet |
| `transfer.import.title` | Importer une armoire | Import a cabinet |
| `transfer.import.reading` | Lecture du fichier… | Reading the file… |
| `transfer.import.checking` | Vérification du fichier… | Checking the file… |
| `transfer.import.backingUp` | Sauvegarde de l'armoire actuelle… | Backing up the current cabinet… |
| `transfer.import.applying` | Import en cours… | Importing… |
| `transfer.import.preview.fileTitle` | Dans le fichier | In the file |
| `transfer.import.preview.exportedAt` | Exporté le %1$@ | Exported on %1$@ |
| `transfer.import.preview.exportedBy` | Par %1$@ %2$@ (%3$@) | By %1$@ %2$@ (%3$@) |
| `transfer.import.preview.formatVersion` | Format du fichier : version %1$lld | File format: version %1$lld |
| `transfer.import.preview.unknownExpiry` **P** | one: `%1$lld boîte sans date de péremption` · other: `%1$lld boîtes sans date de péremption` | one: `%1$lld box with no expiry date` · other: `%1$lld boxes with no expiry date` |
| `transfer.import.preview.currentTitle` | Sur ce téléphone | On this phone |
| `transfer.import.preview.replaceStatement` | L'import remplace toute l'armoire de ce téléphone par celle du fichier. Rien n'est fusionné. | Importing replaces the whole cabinet on this phone with the one in the file. Nothing is merged. |
| `transfer.import.preview.backupStatement` | Une sauvegarde de l'armoire actuelle sera gardée sur ce téléphone. Vous pourrez la restaurer. | A backup of the current cabinet will be kept on this phone. You can restore it. |
| `transfer.import.preview.noBackupNeeded` | L'armoire actuelle est vide : aucune sauvegarde n'est nécessaire. | The current cabinet is empty: no backup is needed. |
| `transfer.import.replace` | Remplacer mon armoire | Replace my cabinet |
| `transfer.import.cancel` | Annuler | Cancel |
| `transfer.import.confirm.title` | Remplacer votre armoire ? | Replace your cabinet? |
| `transfer.import.confirm.message` | Toute l'armoire actuelle sera remplacée par celle du fichier. | The whole current cabinet will be replaced with the one in the file. |
| `transfer.import.confirm.replace` | Remplacer | Replace |
| `transfer.import.done` | Armoire importée | Cabinet imported |
| `transfer.import.done.backupKept` | La sauvegarde de l'armoire précédente est dans « Sauvegardes automatiques ». | The backup of the previous cabinet is in "Automatic backups". |
| `transfer.import.done.close` | Terminé | Done |
| `transfer.import.pendingBanner` | Un fichier d'armoire attend. Terminez ou annulez la saisie en cours. | A cabinet file is waiting. Finish or cancel the current entry. |
| `transfer.import.unavailable` | Import impossible : le stockage de l'armoire n'est pas disponible. | Cannot import: the cabinet storage is unavailable. |
| `transfer.import.wrongFileHint` | Fichier non proposé ? Utilisez « Importer une armoire » et choisissez le fichier .medicabinet. | File not offered? Use "Import a cabinet" and choose the .medicabinet file. |

## Errors (the many validation kinds map to `damaged`)

| Key | fr | en |
|-----|----|----|
| `transfer.error.title` | Import impossible | Couldn't import |
| `transfer.error.tooLarge` | Ce fichier est trop volumineux pour être une armoire MediCabinet. | This file is too large to be a MediCabinet cabinet. |
| `transfer.error.notArchive` | Ce fichier n'est pas une armoire MediCabinet. | This file is not a MediCabinet cabinet. |
| `transfer.error.damaged` | Ce fichier est endommagé ou incomplet. Rien n'a été importé. | This file is damaged or incomplete. Nothing was imported. |
| `transfer.error.newerVersion` | Ce fichier vient d'une version plus récente de MediCabinet. Mettez l'application à jour, puis réessayez. | This file comes from a newer version of MediCabinet. Update the app, then try again. |
| `transfer.error.readFailed` | Le fichier n'a pas pu être lu. | The file could not be read. |
| `transfer.error.backupFailed` | La sauvegarde n'a pas pu être enregistrée. Votre armoire n'a pas été modifiée. | The backup could not be saved. Your cabinet was not changed. |
| `transfer.error.replaceFailed` | L'import a échoué. Votre armoire n'a pas été modifiée. | The import failed. Your cabinet was not changed. |
| `transfer.error.retry` | Réessayer | Try again |
| `transfer.error.close` | Fermer | Close |

Mapping: `tooLarge`, `tooManyItems` → `tooLarge`; `notJSON`, `notArchive` → `notArchive`;
`unsupportedVersion` with a version above the current one → `newerVersion`; every other
`ArchiveErrorKind` → `damaged`; an I/O error while reading → `readFailed`;
`TransferError.backupFailed` → `backupFailed`; any other apply failure → `replaceFailed`.

## Automatic backups

| Key | fr | en |
|-----|----|----|
| `transfer.backups.action` | Sauvegardes automatiques | Automatic backups |
| `transfer.backups.title` | Sauvegardes automatiques | Automatic backups |
| `transfer.backups.footer` | Les 3 dernières armoires remplacées par un import ou une restauration. | The last 3 cabinets replaced by an import or a restore. |
| `transfer.backups.loading` | Chargement des sauvegardes… | Loading backups… |
| `transfer.backups.empty` | Aucune sauvegarde. Une sauvegarde est faite avant chaque import. | No backups. A backup is made before every import. |
| `transfer.backups.error` | Les sauvegardes n'ont pas pu être lues. | The backups could not be read. |
| `transfer.backups.beforeImport` | Avant un import | Before an import |
| `transfer.backups.beforeRestore` | Avant une restauration | Before a restore |
| `transfer.backups.boxes` **P** | one: `%1$lld boîte` · other: `%1$lld boîtes` | one: `%1$lld box` · other: `%1$lld boxes` |
| `transfer.backups.unreadable` | Illisible | Unreadable |
| `transfer.backups.restore` | Restaurer | Restore |
| `transfer.backups.restoreTitle` | Restaurer la sauvegarde du %1$@ | Restore the backup of %1$@ |

## Document type (iOS `InfoPlist.xcstrings` only)

| Key | fr | en |
|-----|----|----|
| type name of `com.medicabinet.archive` | Armoire MediCabinet | MediCabinet cabinet |
