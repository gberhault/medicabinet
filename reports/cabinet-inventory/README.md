# Cabinet inventory — measurements and accessibility review

Date: 2026-10-20 · Spec: [`specs/cabinet-inventory/`](../../specs/cabinet-inventory/)

## Measured (automated, in the gates)

| What | Budget | Result |
|------|--------|--------|
| `CabinetRows.build` on 1 000 boxes (query + location filter), both cores | median of 10 ≤ 100 ms | passes; the whole Swift test (10 builds) takes 0.049 s on a Mac |
| `ScanRemoval.handle` (one lookup + one update) on a 1 000-box cabinet, iOS (real GRDB file) | median of 10 ≤ 100 ms | passes (`ScanViewModelRemovalTests`); the whole test, seeding included, takes 0.044 s |
| Same, Android (Robolectric SQLite) | median of 10 ≤ 100 ms | passes (`ScanRemovalPerformanceTest`) |
| Observation re-query of 1 000 boxes, iOS and Android | ≤ 50 ms | passes (T13 / T14 tests) |

The 100 ms removal budget is a third of the 300 ms "code recognised → removal confirmed" target; the unit tests do not
include the camera or the decoder.

## Measured on a phone (Android)

Samsung Galaxy A35 (SM-A356B, Android 16, 120 Hz screen), debug build installed **side by side** as
`com.medicabinet.app.perf` (`./gradlew -PperfBuild=true :app:assembleDebug :app:assembleDebugAndroidTest`) so the
installed app and its data were never touched; both `.perf` packages were uninstalled afterwards. A seeded cabinet of
1 000 boxes (300 products, 8 categories, 4 % unknown expiry) built by the opt-in instrumented test
`CabinetDeviceMeasurementTest` (it refuses to run unless the package ends in `.perf`).

| What | Budget | Result (median of 10, first runs included) |
|------|--------|--------------------------------------------|
| Query behind the Cabinet tab: `activeBoxes()` on 1 000 boxes | ≤ 50 ms | **53 ms** — just over the budget on this phone; it runs on an I/O thread, never the main thread |
| `CabinetRows.build` with a search, 1 000 boxes | ≤ 100 ms | 68 ms (off the main thread) |
| `ScanRemoval.handle` on a 1 000-box cabinet | ≤ 100 ms (< 300 ms to confirm) | **4 ms** (runs 3, 1, 11, 6, 4, 4, 4, 4, 4, 4) |
| Scrolling the Cabinet list (990 boxes), 8 swipes up and 8 down through the real app, `dumpsys gfxinfo` | no dropped frames | 600 frames; 50th percentile 7 ms, 90th 15 ms, 95th 18 ms, 99th 26 ms; 63 janky frames (10.5 %) by Android's definition |

Reading the scroll result: the screen runs at 120 Hz, so a frame has 8.3 ms, and this is an unoptimised **debug**
build (Compose is markedly slower than in release). The list is not yet "without dropped frames" by that strict
measure; a release build, and the phone at 60 Hz, were not measured. The 53 ms query is the number to watch if the
cabinet grows.

## Not measured — needs an iPhone

Scrolling a 1 000-box Cabinet list and removal latency on the oldest iPhone available: no iPhone was connected, and
nothing is claimed for iOS beyond the simulator tests above.

## Accessibility review (T26)

What was checked, by reading the code and by automated UI tests — **not** a VoiceOver / TalkBack walk-through on a device:

- Every control of the new screens (mode switch, reason chip, candidates sheet, Cabinet list rows, filters, box detail,
  edit form, category picker, take-out actions) has a text label or an accessibility label; icon-only buttons carry a
  label. Statuses are always text plus an icon (`ExpiryStatusLabel`), and Remove mode is text ("Removing boxes"), icon
  and tint, never colour alone.
- Targets are at least 44 pt / 48 dp.
- UI tests run the scanner (Add and Remove), the Cabinet list and the box detail at the largest Dynamic Type size in dark
  mode (iOS) or font scale 2.0 (Android Robolectric). The Remove bar was rearranged on two rows after a font-scale-2.0
  test showed its sound button pushed out of the bar.
- Contrast, computed from the default Material 3 schemes (Android): in the Remove bar (surface at 90 % over the camera,
  worst case black) body text is 12.9:1 in light and 14.6:1 in dark mode, and the `error` border / icon 5.0:1 and
  11.1:1 — all above 4.5:1. On iOS the Remove title and the status texts stay in the primary colour; only icons are
  tinted with system colours (`.red`, `.orange`, `.green`, which Apple adapts to light and dark mode), and the text
  next to each icon carries the meaning. A pixel-level check of the iOS material over a live camera frame needs a device.
- Undo messages are announced (VoiceOver announcement, TalkBack live region / snackbar) and the iOS banner does not
  vanish while VoiceOver focus is on its button.
