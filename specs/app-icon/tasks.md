# App icon — Tasks

Status: **Approved 2026-10-20**
Issue: #46 · Branch: `feature/46-app-icon`

One task = one focused commit (code + tests + this file checked off). Tags: `[shared]`,
`[ios]`, `[android]`. Requirement IDs refer to [`requirements.md`](requirements.md), sections
(§) to [`design.md`](design.md).

Order: source art and validator → Android layers → Android wiring → PNG pipeline → iOS icon
→ Play assets → gate wiring → docs → visual check → versions. T3 and T5 are independent of
each other once T2 and T4 are done.

## 1. Source and script

- [ ] **T1 — Source art and SVG subset validator** `[shared]` (R1, R6; §1.1, §2)
  - Add `design/app-icon/medicabinet-icon.svg`: corrected D2 (94% scale, valley at y = 55) in
    `background` / `foreground`, plus the `wordmark` lockup.
  - Delete the untracked proposals (A–C, D variants, `preview.html`, the old `generate.py`
    and `README.md`).
  - Add `scripts/build-app-icon.py` with the parser and subset validation, and a `check`
    that validates only.
  - *Tests first:* `scripts/tests/test-build-app-icon.py` (stdlib `unittest`). It accepts
    the source and rejects `text`, `image`, `use`, `style`, `foreignObject`, `filter`, an
    external `href`, an unsupported transform (`matrix`, `skewX`) and an unknown paint, each
    with a message naming the element. It also checks that the script imports nothing outside
    the standard library.

## 2. Android

- [ ] **T2 — SVG → VectorDrawable and adaptive-icon generation** `[shared]` (A1, A3–A6, R3–R5; §1.5, §3, §3.1)
  - `build` writes the three `drawable/ic_launcher_*.xml` layers and the two
    `mipmap-anydpi/ic_launcher*.xml` files.
  - `check` regenerates them in memory and compares them byte for byte. It also fails when
    any `-night` launcher resource exists (A6).
  - Commit the generated files.
  - *Tests first:* mapping unit tests: rect → path; gradient → `aapt:attr` with absolute
    coordinates; stroke attributes; `translate`/`scale`/`rotate(a cx cy)` → nested groups in
    SVG order; monochrome = alpha-only paints; the output is identical when run twice. Plus a
    `check` test that fails on an edited XML.
- [ ] **T3 — Use the icon in the Android app** `[android]` (A1, A2, A4, A5; §3, §9)
  - In `AndroidManifest.xml`, set `android:icon="@mipmap/ic_launcher"` and
    `android:roundIcon="@mipmap/ic_launcher_round"`. `check` fails on any `@android:drawable`
    icon.
  - *Tests first:* Robolectric `AppIconTest`:
    - `applicationInfo.icon` and `roundIcon` resolve to the mipmaps;
    - on `sdk = [26]` the drawable is an `AdaptiveIconDrawable`;
    - on `sdk = [33]` its `monochrome` is non-null.

    If Robolectric cannot inflate the gradient on API 26, fall back to resource-ID asserts
    and note it in §12.
  - Then run `bash scripts/android-local-ci.sh`.

## 3. Raster outputs

- [ ] **T4 — PNG pipeline, safe-zone check, hash manifest** `[shared]` (A3, R3–R6; §1.3, §1.4, §4)
  - Chrome lookup through `$CHROME` or the default path, with a clear message when it is
    missing.
  - Rendering from a temporary SVG at an exact size and DSF 1.
  - A stdlib PNG decoder and encoder: unfiltering, re-encoding with a fixed filter and
    compression level, RGB or RGBA output.
  - The safe-zone raster check in `build`.
  - `manifest.json` with the source, script and output hashes, verified by `check`.
  - *Tests first:*
    - PNG encode → decode round trip (RGB and RGBA, every filter type on decode);
    - header reader (dimensions, colour type, size);
    - safe-zone detector on synthetic images (inside passes, one pixel outside fails);
    - manifest mismatch for the source, the script and an output;
    - `CHROME=/nonexistent build` exits non-zero with the message.
- [ ] **T5 — iOS app icon: light, dark, tinted** `[ios]` (I1–I5; §1.6, §2.1, §3)
  - `build` writes `AppIcon.appiconset/Contents.json` (default + `luminosity` dark and tinted)
    and the three 1024×1024 RGB PNGs from the palette maps.
  - `check` verifies the header rules (no alpha, size) and regenerates `Contents.json`.
  - *Tests first:* `check` tests for an iOS PNG with alpha and a wrong size, and an iOS unit
    test `AppIconTests`: `CFBundleIcons.CFBundlePrimaryIcon.CFBundleIconName == "AppIcon"`
    in the built bundle.
  - Then run `bash scripts/ios-local-ci.sh`.
- [ ] **T6 — Play listing assets** `[shared]` (P1–P4, X1; §2.2, §4)
  - `build` writes `play/icon-512.png` (RGBA, full-bleed) and
    `play/feature-graphic-1024x500.png` (RGB: foreground plus the vector wordmark, inside the
    900×400 area).
  - `check` enforces P1–P2 (dimensions, colour type, size limits), P4 (nothing under
    `android/` or `ios/`) and the X1 per-app budget (≤ 100 KB).
  - *Tests first:* a `check` test for each rule (wrong size, RGB Play icon, an oversized
    file, an asset copied into `android/`, an app over budget).

## 4. Gate, docs, verification

- [ ] **T7 — Gate wiring and break test** `[shared]` (R5, X2; §4.1)
  - Add the step "shared: app icon is generated from its source" to `scripts/local-ci.sh`.
    It runs `test-build-app-icon.py`, then `build-app-icon.py check`, then the new
    `scripts/tests/test-app-icon-break.sh`.
  - The break test runs against a copy of the repository and covers: the committed state
    passes; then it must fail after editing the source, editing an XML, replacing a PNG,
    giving an iOS PNG an alpha channel, adding a `<text>` to the source, or pointing the
    manifest at `sym_def_app_icon`.
  - *Test:* `bash scripts/local-ci.sh shared` is green, and each break case reports `ok`.
- [ ] **T8 — Documentation** `[shared]` (X3, R2; §10)
  - New `docs/app-icon.md`: the source and its subset, the rights rules and the reason
    there's no cross or caduceus, how to rebuild, the variants, the Play requirements.
  - Link it from `docs/README.md`.
  - `docs/development.md`: Chrome as an optional prerequisite, needed only to rebuild the
    icon.
  - `CLAUDE.md`: add `design/` to Project Structure and a Common Task Map row "Change the
    app icon".
  - *Test:* the links resolve, and `local-ci.sh shared` is green (CLAUDE.md changed).
- [ ] **T9 — Visual check on devices** `[ios]` `[android]` (A3–A6, I3–I5; §9)
  - Android, on `emulator-5554` only, never an unnamed device: API 26, then API 33+ with
    themed icons on, in circle and squircle shapes.
  - iOS simulators: iOS 17, then iOS 18+ in light, dark and tinted.
  - Screenshots go in the PR body, not committed. No commit unless a fix is needed; a fix
    goes back through the source and `build`.
  - Also run `build` twice and check that `git status` stays clean (R4).

## 5. Release

- [ ] **T10 — Version bump, PATCH on both apps** `[ios]` `[android]` (X5; §11)
  - iOS: `MARKETING_VERSION` +0.0.1 and `CURRENT_PROJECT_VERSION` +1, plus a
    `ios/CHANGELOG.md` entry "New app icon".
  - Android: `versionName` +0.0.1 and `versionCode` +1, plus an `android/CHANGELOG.md`
    entry.
  - Versions are taken from `develop` at the time (0.4.1, or 0.5.1 if #26 has landed).
  - *Test:* `check-version.sh ios` and `android`, then the full `bash scripts/local-ci.sh`.
