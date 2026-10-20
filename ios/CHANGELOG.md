# Changelog

Newest entry first. The top entry must match `MARKETING_VERSION` in `project.yml`
(checked by `scripts/check-version.sh`).

## 0.1.1 — 2026-10-19

The medicine reference data, bundled; nothing scans yet.

- The French public medicines database (BDPM, 20 896 boxes, data of 2026-10-18) is bundled, so a box can be named and
  filed into one of twelve household categories offline once scanning arrives. Products BDPM has dropped stay known.
- Settings → About cites the data source, shows the date of the data and states that MediCabinet is not affiliated
  with or endorsed by ANSM, HAS or the national health insurance.
- The category names are available in French and English.

## 0.1.0 — 2026-10-19

First build: project foundation, no end-user feature yet.

- iOS app shell with four tabs (Scan, Cabinet, Expiring, Settings), French and English.
- Local cabinet database with versioned migrations and an automatic copy before every update.
- Settings → About with version and the "no medical advice" notice.
