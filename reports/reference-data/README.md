# Reference data (epic #7) — measurements

Measured 2026-10-19 on the data of the 2026-10-18 BDPM snapshot (20 896 presentations, rules v1).

## App size (target: < 50 MB download)

| | Before | After | Delta |
|--|--------|-------|-------|
| `reference.sqlite` | — | 1 740 800 bytes (1.66 MB) | +1.74 MB installed |
| Android debug APK | 16 776 136 bytes | 17 268 987 bytes | **+0.49 MB** (the database deflates to 0.45 MB inside the APK, the manifest to 551 bytes) |
| Android installed copy | — | `noBackupFilesDir/reference/reference.sqlite`, 1.74 MB | +1.74 MB of internal storage (excluded from backups) |
| iOS Release simulator `.app`, zipped | 5 134 183 bytes | 5 517 092 bytes | **+0.38 MB** (a signed IPA compresses the same file the same way) |

Far under the 50 MB target; the 8 MB database budget of the build script leaves room for years of cumulative growth.

## Cold start

Nothing is added to the launch path: both apps only allocate the lazy reference-data object. Tests assert it
(`bootstrapNeverOpensTheReferenceData` on iOS, `nothingIsInstalledOrOpenedUntilFirstUseAndOnlyOnce` and
`launchNeverTouchesTheReferenceDataAndAboutIsWhatOpensIt` on Android).

Android, Pixel 6 emulator (API 37, arm64), `bash scripts/android-measure-launch.sh emulator-5554`, 5 runs each, develop
and this branch alternated on the same booted emulator:

| Build | Runs (average of 5) | Mean |
|-------|---------------------|------|
| develop (baseline) | 1735, 2176, 1811, 1564 ms | 1 822 ms |
| this branch, first version | 2072, 2361 ms | 2 217 ms — **+22 %** |
| this branch, final | 1679, 1631 ms | 1 655 ms — within noise of the baseline |

The first version created the About view model when the main scaffold was composed, and that view model asked the
reference data for its status at once, so the first launch copied and verified the database in the background. The
measurement caught it; the view model now asks only when the About screen collects its state, and a regression test
counts the calls. Emulator timing is noisy and slower than a phone (the foundation report measured 1.21 s on a fresh
emulator); re-measure on a real phone with `bash scripts/android-measure-launch.sh <serial>`.

## Lookup and open

Asserted on the real bundled database with generous budgets (several runs), on the iOS simulator and on Robolectric:

| Metric | Target | Budget asserted | Typical |
|--------|--------|-----------------|---------|
| Lookup (CIP13 → product) | < 50 ms | p99 < 50 ms, median < 5 ms over 500 lookups | ~15 µs per read on a desktop (prototype), a few ms at most through the app layers |
| First open (iOS) | off the main thread | < 500 ms | ~12 ms |
| First install + open (Android) | off the main thread | < 3 s | the copy, two hashes, the canonical content hash and `quick_check` of 1.7 MB |
