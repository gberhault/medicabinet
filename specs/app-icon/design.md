# App icon — Design

Status: **Approved 2026-10-20**
Issue: #46 · Requirements: [`requirements.md`](requirements.md) · Platforms: iOS + Android

The icon is drawn **once**, in one SVG that uses a small, checked subset of SVG. One stdlib
Python script turns it into the Android vector layers, the iOS PNGs and the Play assets. A
`check` mode runs in the shared gate without any rasterizer. No app code changes beyond the
Android manifest. Section numbers are referenced from `tasks.md`.

## 1. Key decisions

### 1.1 Two geometric corrections to D2 — approved 2026-10-20

As approved, D2's leg caps reach 34.1 units from the centre: outside the 33-unit safe zone,
which breaks A3. The capsule is also 0.84 units from the M's valley, so the two merge at
48 dp. The source art therefore applies two corrections:

| | D2 as approved | D2 corrected |
|--|--|--|
| Foreground scale about the centre | 100% | **94%** |
| M valley (middle point) | y = 57 | **y = 55** |
| Leg caps, distance from centre | 34.1 (outside) | **32.0 (inside)** |
| Gap between capsule and valley | 0.84 | **2.55** |

Colours, stroke weight, capsule size and layout are unchanged. Side by side, the two versions
are hard to tell apart, except at 36–48 dp, where the corrected capsule stays separate from
the M.

### 1.2 The script lives in `scripts/`, the art in `design/app-icon/` — replaces decision 2, approved 2026-10-20

Decision 2 put the script next to the art. Every other build and gate script lives in
`scripts/` and is covered by `scripts/tests/`, with `build-reference-db.py` as the model.
So the script is **`scripts/build-app-icon.py build | check`**, and `design/app-icon/` holds
the source and the Play assets only.

### 1.3 Rasterizer: headless Google Chrome, needed only for `build` (open point 1)

The PNGs (iOS ×3, Play ×2) are rendered by headless Chrome
(`--headless --screenshot --window-size=W,H --force-device-scale-factor=1`). Chrome is
already on the development Mac, and Homebrew's `rsvg-convert` would be a new prerequisite.
The script finds Chrome through `$CHROME`, or the default macOS path, and stops with a clear
message when it is missing (R6).

Chrome's screenshot is decoded and re-encoded by the script itself (stdlib `zlib`, fixed
filter and compression level). So with the same Chrome, two builds give byte-identical files
(R4), and the script controls the colour type: RGB for iOS and the feature graphic, RGBA for
the 32-bit Play icon.

### 1.4 Staleness: a manifest of hashes, not a pixel comparison (open point 2)

`design/app-icon/manifest.json` records the SHA-256 of the source SVG and of the script, and
the SHA-256 of each generated PNG. `check` fails when:
- the source or the script no longer matches its recorded hash: "run build";
- a PNG no longer matches its recorded hash: it was edited by hand.

The text outputs (Android XML, iOS `Contents.json`) need no hash: `check` regenerates them
in memory and compares them byte for byte. This keeps the gate free of Chrome, and Chrome
version drift cannot break it (R5).

### 1.5 Android: vector layers in `mipmap-anydpi`, no PNGs (A1, A5)

minSdk is 26, so `<adaptive-icon>` is always available. The layers are `<vector>` drawables:
the gradient uses `aapt:attr`, supported from API 24. The folder is `mipmap-anydpi`, not
`-v26`, because a `-v26` qualifier is redundant at minSdk 26. `<monochrome>` is ignored
before API 33 and used for themed icons from 33 (A4).

### 1.6 iOS: one 1024 px image per appearance, no Icon Composer yet

`AppIcon.appiconset` gets the single-size 1024 entry with the `luminosity` appearances
`dark` and `tinted` (Xcode 16+ format). iOS 17 uses the default entry (I3). On iOS 26+ the
system gives these icons its glass rendering automatically. A layered Icon Composer `.icon`
file would give finer control, but the format is produced by Apple's app rather than
documented for scripting, and its iOS 17 fallback would need checking. That stays a possible
follow-up issue, outside #46.

## 2. Source art — `design/app-icon/medicabinet-icon.svg`

One file, viewable in any browser. It shows the icon (`0…108`) next to the wordmark, and the
script reads the parts by `id`:

| `id` | Content | Used by |
|------|---------|---------|
| `background` | 108×108 rect, linear gradient `#0D9488` → `#115E59` (top → bottom) | every icon |
| `foreground` | the M (two stroked paths, width 10, round caps and joins) + the capsule (two filled paths, `#FBBF24` / `#FFFFFF`), wrapped in the 94% scale of §1.1 | every icon |
| `wordmark` | "MediCabinet" as stroked geometric letter paths (same stroke style as the M, round caps), white | feature graphic only |

**Allowed SVG subset** (anything else makes `build` and `check` fail and name the element,
which enforces R1 mechanically):
- elements `svg`, `defs`, `linearGradient` + `stop`, `g`, `rect`, `path`;
- `g transform` limited to `translate(x y)`, `scale(s)` and `rotate(a cx cy)`;
- paint as `#RRGGBB` or `url(#id)` to a local gradient; stroke attributes `stroke-width`,
  `stroke-linecap`, `stroke-linejoin`.

So `text`, `font`, `image`, `use`, `foreignObject`, `filter`, `style` and external
references are rejected. R2 (no crosses or caduceus, no imitation of a system icon) is a
design rule checked in review and written in `docs/app-icon.md`.

### 2.1 Variants (palette maps in the script)

| Variant | Background | M | Capsule (left / right) |
|---------|-----------|---|---------|
| light (default; Android, iOS light, Play) | `#0D9488` → `#115E59` | `#FFFFFF` | `#FBBF24` / `#FFFFFF` |
| iOS dark | `#134E4A` → `#042F2E` | `#99F6E4` | `#FBBF24` / `#99F6E4` |
| iOS tinted | `#000000` | `#FFFFFF` | `#FFFFFF` / `#9CA3AF` (keeps the two halves apart in grey) |
| Android monochrome | none (alpha only) | opaque | opaque |

### 2.2 Feature graphic layout (P2, P3)

1024×500 on the light gradient. The foreground is drawn without its tile, about 300 px tall,
on the left third. The wordmark is white, vertically centred, to its right. Everything stays
inside a centred 900×400 area, away from the edges Play may crop or cover.

## 3. Generated files

```
design/app-icon/
  medicabinet-icon.svg               # source art (§2), hand-edited
  manifest.json                      # hashes (§1.4), written by build
  play/icon-512.png                  # 512×512 RGBA, full-bleed square (P1)
  play/feature-graphic-1024x500.png  # 1024×500 RGB (P2)
android/app/src/main/res/
  drawable/ic_launcher_background.xml
  drawable/ic_launcher_foreground.xml
  drawable/ic_launcher_monochrome.xml
  mipmap-anydpi/ic_launcher.xml        # <adaptive-icon> background + foreground + monochrome
  mipmap-anydpi/ic_launcher_round.xml  # same layers
ios/MediCabinet/Resources/Assets.xcassets/AppIcon.appiconset/
  Contents.json                      # default + dark + tinted entries
  AppIcon-light.png  AppIcon-dark.png  AppIcon-tinted.png   # 1024×1024 RGB, no alpha (I1, I2)
```

Every generated file starts with a "generated by `scripts/build-app-icon.py`, do not edit"
comment where the format allows one (XML). `AndroidManifest.xml` changes once, by hand:
`android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"` (A1,
A2). `ios/project.yml` already names `AppIcon` and needs no change.

### 3.1 SVG → VectorDrawable mapping

| SVG | VectorDrawable |
|-----|----------------|
| `viewBox 0 0 108 108` | `android:width/height="108dp"`, `viewportWidth/Height="108"` |
| `rect` | `path` with `pathData="M x,y h w v h h -w z"` |
| `path d` | `pathData` unchanged (VectorDrawable accepts SVG path syntax, arcs included) |
| fill `#RRGGBB` / stroke | `fillColor` / `strokeColor`, `strokeWidth`, `strokeLineCap`, `strokeLineJoin` |
| `url(#gradient)` | `<aapt:attr name="android:fillColor"><gradient android:type="linear" …>`, with the bounding-box coordinates converted to absolute viewport coordinates |
| `translate` / `scale` / `rotate(a cx cy)` | one nested `<group>` per operation (`translateX/Y`; `scaleX/Y`; `rotation` + `pivotX/Y`), outermost first, which matches SVG's order |

## 4. Script — `scripts/build-app-icon.py`

Stdlib only (`xml.etree`, `zlib`, `struct`, `hashlib`, `json`, `subprocess`).

- **`build`**:
  1. Parse and validate the subset (§2).
  2. Write the Android XML and the iOS `Contents.json`.
  3. Render each PNG through Chrome from a temporary SVG; decode, re-encode (§1.3) and write it.
  4. **Safe-zone check (A3):** render the foreground in white on black at 2 px per unit, and
     fail if any pixel brighter than a small anti-aliasing threshold lies outside the
     66-unit circle.
  5. Write `manifest.json`.
- **`check`** (no Chrome):
  1. Validate the subset.
  2. Regenerate the text outputs and compare them byte for byte.
  3. Verify the source, script and PNG hashes against `manifest.json` (R5).
  4. Read each PNG header: dimensions, colour type (no alpha for iOS and the feature graphic,
     RGBA for the Play icon), file size ≤ 1 024 KB for the Play icon and ≤ 15 MB for the
     feature graphic (I1, P1, P2).
  5. Check that `AndroidManifest.xml` points at `@mipmap/ic_launcher` and
     `@mipmap/ic_launcher_round` and at no `@android:drawable/…` icon (A1, A2).
  6. Check that the bytes each app bundles stay ≤ 100 KB (X1).
  7. Check that the Play assets are not under `android/` or `ios/` (P4).
- **Options:** `--root` lets the break test run against a copy of the repository.

### 4.1 Gate wiring

The `shared` part of `scripts/local-ci.sh` gets a new step, "shared: app icon is generated
from its source", which runs `python3 -I scripts/build-app-icon.py check` and then
`bash scripts/tests/test-app-icon-break.sh`.

The break test copies the relevant files to a temporary root and expects `check` to:
- pass on the committed state;
- fail after editing the source;
- fail after editing a generated XML;
- fail after replacing a PNG;
- fail with an alpha channel in an iOS PNG;
- fail with a `<text>` element in the source;
- fail with the manifest pointing back at `sym_def_app_icon`.

## 5. Flows

**Changing the icon:**
1. Edit `medicabinet-icon.svg`.
2. Run `python3 -I scripts/build-app-icon.py build`.
3. Review the PNGs and look at the icon on a simulator or emulator.
4. Commit the source, the generated files and `manifest.json` together.
5. The gate's `check` proves they match.

**Gate:** `check` runs in the `shared` step. A source edit without a rebuild fails with
"source changed: run scripts/build-app-icon.py build".

## 6. Performance impact

Not on the scan path, persistence or import/export. App size: Android adds a few KB of XML.
iOS adds three 1024 px PNGs, which compress well because the gradient rows are flat (expected
≤ 60 KB together), and `actool` derives the smaller sizes. The X1 budget (≤ 100 KB per app) is
checked by `check`. Cold start is unaffected.

## 7. Privacy impact

None: no data, no permission, no privacy-manifest entry, no network (X4).

## 8. i18n impact

None: no user-facing string. "MediCabinet" in the feature graphic is the product name, drawn
as shapes, and is not localised.

## 9. Test strategy

| Req | Test | Kind |
|-----|------|------|
| R1 | subset validation in `build`/`check`; break test with `<text>` | gate |
| R2 | review checklist in `docs/app-icon.md`; PR review | review |
| R3, R5 | `check` (text regeneration + hash manifest); break test (edited source, XML, PNG) | gate |
| R4 | `build` run twice in the build task, `git status` clean afterwards | manual, once |
| R6 | script imports stdlib only; a missing-Chrome run prints the message (break test, `CHROME=/nonexistent`, `build`) | gate |
| A1, A2 | Robolectric `AppIconTest`: `applicationInfo.icon == R.mipmap.ic_launcher`, `roundIcon == R.mipmap.ic_launcher_round`; `check` manifest rule | unit + gate |
| A3 | safe-zone raster check in `build` | build |
| A4 | `AppIconTest` on `@Config(sdk = [33])`: the loaded drawable is an `AdaptiveIconDrawable` with a non-null `monochrome` | unit |
| A5, A6 | `AppIconTest` on `@Config(sdk = [26])`: the drawable loads as `AdaptiveIconDrawable`; light/dark: no `-night` icon resources (`check`) | unit + gate |
| I1, I2 | `check` PNG header rules + `Contents.json` regeneration | gate |
| I3, I4, I5 | iOS unit test `AppIconTests`: `CFBundleIcons.CFBundlePrimaryIcon.CFBundleIconName == "AppIcon"` in the built bundle; manual check on an iOS 18+ simulator in light, dark and tinted modes (screenshots in the PR) | unit + manual |
| P1–P4 | `check` (dimensions, colour type, size, location) | gate |
| X1 | `check` size budget | gate |
| X2 | `bash scripts/local-ci.sh` | gate |
| X3 | docs task | review |
| X4 | gates already enforce no permission, network or string changes (`PrivacyTest`, string checks) | gate |
| X5 | `check-version.sh ios` / `android` | gate |

**Manual visual check:** emulator `emulator-5554` with API 26 and API 33+ (themed icons on),
in circle and squircle shapes. iPhone simulator on iOS 17 and 18+, in light, dark and tinted.
The screenshots go in the PR body, not committed.

## 10. Documentation

- **New `docs/app-icon.md`:** the source file and its subset, the rights rules (R1–R2, why
  no crosses or caduceus), how to rebuild, the variants, the Play asset requirements. It is
  linked from `docs/README.md`.
- **`docs/development.md`:** Chrome as an optional prerequisite, needed only to rebuild the
  icon.
- **`CLAUDE.md`:** `design/` in Project Structure, plus a Common Task Map row "Change the app
  icon".

## 11. Version

PATCH on both apps (open point 3), bumped as the branch's last commit against `develop` at
that time:
- 0.4.0 → 0.4.1 if #46 merges first;
- 0.5.0 → 0.5.1 if export/import (#26) lands first.

The build numbers go up by one each. The changelog entry reads "New app icon".

## 12. Risks

- **Chrome rendering drift:** a newer Chrome may shift anti-aliased pixels, so a rebuild can
  produce different PNG bytes. That is harmless: the manifest records the new hashes, and
  `check` never re-renders.
- **iOS 26+ glass rendering:** the system styles flat icons itself, so the result is checked
  on a simulator. If it looks poor, the Icon Composer follow-up (§1.6) becomes a priority.
- **Monochrome legibility:** the capsule and the M become one silhouette. The 2.55-unit gap
  (§1.1) keeps them apart at 48 dp, and this is confirmed on the emulator with themed icons.
- **Robolectric and `AdaptiveIconDrawable`:** if Robolectric cannot inflate the `aapt:attr`
  gradient on API 26, the test falls back to asserting the resource IDs, and A5 is verified
  manually on the API 26 emulator.
