# Changelog — Android

Newest entry first. The top entry must match `versionName` in `app/build.gradle.kts`
(checked by `scripts/check-version.sh android`).

## 0.1.0 — 2026-10-19

First build: project foundation, no end-user feature yet.

- Android app shell with four destinations (Scan, Cabinet, Expiring, Settings), French and English.
- Local cabinet database (the same SQLite schema as iOS) with versioned migrations and an automatic copy before every update.
- Settings → About with version and the "no medical advice" notice.
- No permissions; the cabinet and its backup copies are included in Android Auto Backup.
