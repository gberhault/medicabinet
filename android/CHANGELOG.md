# Changelog — Android

Newest entry first. The top entry must match `versionName` in `app/build.gradle.kts`
(checked by `scripts/check-version.sh android`).

## 0.1.1 — 2026-10-19

The medicine reference data, bundled; nothing scans yet.

- The French public medicines database (BDPM, 20 896 boxes, data of 2026-10-18) is bundled, so a box can be named and
  filed into one of twelve household categories offline once scanning arrives. Products BDPM has dropped stay known.
  The database is copied to internal storage on first use (1.7 MB, excluded from backups) and checked against its
  manifest.
- Settings → About cites the data source, shows the date of the data and states that MediCabinet is not affiliated
  with or endorsed by ANSM, HAS or the national health insurance.
- The category names are available in French and English.

## 0.1.0 — 2026-10-19

First build: project foundation, no end-user feature yet.

- Android app shell with four destinations (Scan, Cabinet, Expiring, Settings), French and English.
- Local cabinet database (the same SQLite schema as iOS) with versioned migrations and an automatic copy before every update.
- Settings → About with version and the "no medical advice" notice.
- No permissions; the cabinet and its backup copies are included in Android Auto Backup.
