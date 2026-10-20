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

## Not measured — needs a phone

No emulator was running and the phone attached to this machine was not named for this work, so nothing below was
run and no number is claimed:

1. Scrolling a 1 000-box Cabinet list without dropped frames, on the oldest iPhone and Android phone available.
2. Code recognised → removal confirmed < 300 ms on a phone (suggested method as in `reports/bulk-scan/`).

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
