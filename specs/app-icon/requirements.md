# App icon — Requirements

Status: **Approved 2026-10-20**
Issue: #46 · Branch: `feature/46-app-icon` · Platforms: iOS + Android

## Introduction

Today both apps ship with no icon of their own: Android shows the system default
(`@android:drawable/sym_def_app_icon`) and the iOS `AppIcon` set is empty. The app cannot
be published like that, and on a home screen it cannot be told apart from a broken install.
This change gives MediCabinet one identity on both platforms, the **D2 monogram**: a white
geometric "M" with an amber and white capsule in its valley, on a teal gradient. It is drawn
once, as original artwork with no third-party rights, and every platform asset is generated
from that single source.

> We are giving both apps the D2 monogram icon (Android adaptive and themed; iOS light, dark
> and tinted), plus the Google Play 512 px icon and the 1024×500 feature graphic, all
> generated from one source SVG, so that MediCabinet has a publishable identity with no
> third-party rights. We are **not** changing the splash screen or in-app branding, and
> **not** writing store listing text or screenshots.

### Decisions taken in the challenge (2026-10-20, recorded on #46)

1. **Concept.** D2: the D monogram with the capsule 25% larger than first proposed, so it
   stays readable at 48 dp. Gradient `#0D9488` → `#115E59`, amber `#FBBF24`, white.
2. **Source.** One source SVG plus a regeneration script in `design/app-icon/`. The platform
   assets are generated from it and committed. Proposals A, B and C are not kept.
3. **iOS.** Light, dark and tinted variants (iOS 18 appearances). iOS 17 uses the light one.
4. **Android.** An adaptive icon from vector layers plus a `monochrome` layer. No per-density
   PNGs, because minSdk 26 already supports adaptive icons.
5. **Feature graphic.** The icon and the name "MediCabinet" on the teal gradient, no tagline.
   The name is drawn as vector shapes, not set in a font.

## Glossary

- **Source art**: the single SVG in `design/app-icon/` that every asset derives from.
- **Adaptive canvas**: Android's 108×108 dp icon canvas. Launchers show at least the central
  72×72 dp through a mask (circle, squircle, rounded square…).
- **Safe zone**: the 66 dp diameter circle at the centre of the adaptive canvas. Content
  inside it is never clipped by any mask.
- **Themed icon**: Android 13+ icon tinted by the system from the `monochrome` layer.
- **Play icon**: the 512×512 listing icon uploaded to the Play Console.
- **Feature graphic**: the 1024×500 banner on the Play listing.

## User stories and acceptance criteria

Requirement IDs: **R** source art, **A** Android launcher icon, **I** iOS app icon, **P** Play
listing assets, **X** cross-cutting.

### R — Source art

*As the product owner, I want the icon drawn once, as artwork we own outright, so that I can
publish it anywhere and change it later without redrawing every asset.*

1. The source art SHALL be original vector geometry only. It SHALL contain no font, no
   embedded raster image, no clip-art or icon-library element, and no output of an
   image-generation model.
2. The art SHALL NOT use a red cross, a green (pharmacy) cross, a caduceus or a Rod of
   Asclepius, and SHALL NOT imitate a Google or Apple system icon.
3. Every committed platform asset (R5) SHALL be produced from the source art by one
   committed script, run with a single documented command.
4. WHEN the script is run twice on the same source, it SHALL produce identical files.
5. WHEN the source art changes and the generated assets are not regenerated, a gate check
   SHALL fail. This covers the Android layers, the iOS PNGs and the Play assets.
6. The script SHALL use only the Python standard library plus tools already required for
   development on macOS (documented in `docs/development.md`). It SHALL NOT add a
   third-party dependency to either app.

### A — Android launcher icon

*As an Android user, I want MediCabinet's own icon on my home screen and in my app drawer,
whatever launcher and theme I use, so that I can find the app at a glance.*

1. The app SHALL declare `android:icon` and `android:roundIcon` pointing at an adaptive icon
   made of a background layer, a foreground layer and a `monochrome` layer, all vector
   drawables.
2. The manifest SHALL NOT reference `@android:drawable/sym_def_app_icon`, or any other system
   icon, as the app icon.
3. The M and the capsule SHALL lie entirely inside the safe zone, so no launcher mask clips
   them.
4. WHEN the device uses themed icons (Android 13+), the launcher SHALL show the monochrome
   layer: the M and the capsule as one silhouette.
5. On Android 8.0 to 12 the adaptive icon SHALL display without any extra raster fallback.
6. The icon SHALL be identical in light and dark system themes. The background layer SHALL be
   opaque, so the icon never takes the wallpaper's colour.

### I — iOS app icon

*As an iPhone user, I want MediCabinet's own icon on my home screen in every icon appearance I
choose, so that it stays recognisable and legible.*

1. `AppIcon` SHALL contain a 1024×1024 PNG with no alpha channel for the light (default)
   appearance.
2. It SHALL also contain a dark-appearance variant (dark background, teal and amber
   foreground) and a tinted-appearance variant (a greyscale foreground the system tints).
3. WHEN the device runs iOS 17, the system SHALL show the light variant.
4. WHEN the device runs iOS 18 or later and the user picks dark or tinted icons, the system
   SHALL show the matching variant, not an automatic darkening of the light one.
5. The iOS and Android icons SHALL show the same artwork: same M, same capsule, same colours
   for the light appearance.

### P — Google Play listing assets

*As the product owner, I want the Play listing assets ready and compliant, so that I can
publish to Google Play without redoing the artwork.*

1. The Play icon SHALL be a 512×512, 32-bit PNG of at most 1 024 KB. It SHALL be a
   full-bleed square with no rounded corners and no drop shadow baked in, since Google Play
   adds both.
2. The feature graphic SHALL be a 1024×500 PNG or JPEG with no alpha channel, at most 15 MB,
   showing the icon and the name "MediCabinet" on the teal gradient. It SHALL show no
   tagline and no medical claim.
3. The name in the feature graphic SHALL be vector shapes from the source art, not text set
   in a font.
4. The Play assets SHALL be committed under `design/app-icon/` (they are listing material,
   not app resources) and SHALL NOT be bundled in either app.

### X — Cross-cutting

1. Neither app SHALL grow by more than 100 KB because of the icon.
2. Both gates SHALL pass (`bash scripts/local-ci.sh`), with no lint or detekt baseline
   regenerated to absorb new findings.
3. `docs/` SHALL describe where the source art lives, how to regenerate the assets, and the
   rights rules of R1–R2.
4. The change SHALL add no user-facing string, permission, privacy-manifest entry or network
   access.
5. Each app SHALL bump its version and changelog entry ("new app icon"). A version bump
   shows the new icon as a user-visible change.

## Out of scope

- Splash / launch screen, in-app logo or About-screen artwork.
- Store listing text, screenshots, promo video, Play Console setup or submission.
- An app-icon badge (count of expiring boxes).
- Alternate icons the user can choose (iOS `alternateIcons`, Android activity aliases).
- Trademark registration. Only a search for anything too close happens before publishing.
- Notification icon changes (`ic_notification_reminder` stays as it is).

## Open points to confirm during design

1. **Rasterizer for the iOS and Play PNGs.** I propose headless Google Chrome, already on the
   development Mac, with the script failing clearly when it is missing. The alternative,
   `rsvg-convert` via Homebrew, would be a new development prerequisite.
2. **R5 staleness check.** The Android vector layers are plain text and can be compared
   exactly. The PNGs depend on the rasterizer version. I propose committing a hash of the
   source art next to the PNGs and failing the gate when it does not match, rather than
   comparing pixels.
3. **Version increment.** I propose **PATCH** on both apps: a new icon is a small
   user-visible tweak, not a feature.
