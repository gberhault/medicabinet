# App icon

One drawing, generated into every asset. The icon is a white geometric "M" with an amber and white capsule in its
valley, on a teal gradient. Specified in [`specs/app-icon/`](../specs/app-icon/) (issue #46).

## Where things are

| What | File |
|------|------|
| Source art (the only file to edit) | [`design/app-icon/medicabinet-icon.svg`](../design/app-icon/medicabinet-icon.svg) |
| Generator and checker | [`scripts/build-app-icon.py`](../scripts/build-app-icon.py) |
| Hashes of the source, the script and every PNG | `design/app-icon/manifest.json` |
| Android launcher icon (adaptive, themed) | `android/app/src/main/res/drawable/ic_launcher_*.xml`, `mipmap-anydpi/ic_launcher*.xml` |
| iOS icon (light, dark, tinted) | `ios/MediCabinet/Resources/Assets.xcassets/AppIcon.appiconset/` |
| Google Play listing (not bundled in either app) | `design/app-icon/play/icon-512.png`, `feature-graphic-1024x500.png` |

Everything except the source is generated: do not edit it by hand.

## Changing the icon

```bash
python3 -I scripts/build-app-icon.py build     # regenerate everything (needs Google Chrome, see below)
python3 -I scripts/build-app-icon.py check     # what the gate runs: no Chrome needed
```

1. Edit `medicabinet-icon.svg`.
2. Run `build`. It stops if the foreground leaves the 66-unit safe zone, which every Android launcher mask keeps.
3. Look at the result on an Android emulator (circle and squircle masks, themed icons on) and on an iOS 18+
   simulator in light, dark and tinted modes.
4. Commit the source and the generated files together. `check` fails when they disagree.

`build` renders the PNGs with headless Google Chrome (`CHROME=/path/to/chrome` overrides the default macOS path).
`check` never needs it: it regenerates the text files (Android XML, `Contents.json`) in memory and compares the PNGs
by hash, so a different Chrome version can never break the gate. `build` run twice gives identical files.

## The source art

Only a small SVG subset is accepted (`svg`, `defs`, `linearGradient`/`stop`, `g`, `rect`, `path`; `translate`,
`scale` and `rotate` transforms; `#RRGGBB` or gradient paints). Text, images, `use`, filters and styles make
`build` and `check` fail. That keeps the artwork free of fonts and embedded or third-party shapes.

- `background` and `foreground` are the two layers of the 108×108 adaptive-icon canvas.
- `wordmark` ("MediCabinet" drawn as strokes, not set in a font) is used only in the Play feature graphic.

## Variants

| Asset | Look |
|-------|------|
| Android adaptive icon, iOS light, Play | teal gradient, white M, amber and white capsule |
| Android monochrome (Android 13+ themed icons) | the M and the capsule as one opaque silhouette; the system tints it |
| iOS dark | deep teal background, mint M, amber capsule |
| iOS tinted | greyscale on black (colours darker than a luminance floor become the background) |

iOS 17 shows the light icon; the dark and tinted ones apply from iOS 18. iOS and Play use the central 72 of the
108 units, full-bleed, so the system and Play add their own rounded corners.

## Rights

The artwork is original geometry written for MediCabinet: no font, clip-art, icon library, stock image or
image-generation model, so there is nothing to license or credit. Deliberately avoided:

- the **red cross** (an emblem protected by the Geneva Conventions and French law);
- the **green pharmacy cross** (in France it means "this is a pharmacy");
- the caduceus / Rod of Asclepius (implies medical authority; MediCabinet is an inventory tool, not a medical
  device);
- the look of Google or Apple system icons.

Before publishing, search the trademark registers (INPI, EUIPO) for anything too close to the shape and the name.

## Google Play requirements

- Listing icon: 512×512, 32-bit PNG, at most 1 024 KB, full-bleed square (no baked corners or shadow).
- Feature graphic: 1024×500 PNG or JPEG, no alpha, at most 15 MB, key content inside the central 900×400 area.
- `check` enforces all of this, plus: each app's icon files weigh at most 100 KB, and no Play asset sits inside
  `android/` or `ios/`.
