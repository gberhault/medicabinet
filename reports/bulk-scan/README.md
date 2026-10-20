# Bulk scan — measurements and what is still to measure

Date: 2026-10-19 · Spec: [`specs/bulk-scan/`](../../specs/bulk-scan/)

## Measured (automated, in the gates)

| What | Budget | Result |
|------|--------|--------|
| Evaluate + add one box on a 1 000-box cabinet, iOS (real GRDB file on the simulator) | median of 10 ≤ 100 ms | passes (`ScanIntakePerformanceTests`) |
| Evaluate + add one box on a 1 000-box cabinet, Android (Robolectric SQLite) | median of 10 ≤ 100 ms | passes (`ScanIntakePerformanceTest`) |
| `ScanDebouncer`, 5 payloads × 10 000 frames, both cores | < 500 ms | passes |
| Debug APK size (all four ABIs, unminified) | < 50 MB | 28.6 MB |
| zxing-cpp native libraries (per ABI) | — | 1.2–1.9 MB |

The 100 ms budget is a third of the 300 ms "code recognised → box confirmed on screen" target and leaves room for the
UI work; the unit tests do not include the camera or the decoder.

## Not measured yet — needs a phone

These cannot be produced in a simulator or emulator, and no number is claimed:

1. **Code recognised → confirmed on screen < 300 ms**, and **≥ 1 box / 2 s sustained**, on an iPhone and on the Android
   phone you name. Suggested method: a signpost / `Trace` section around evaluate + add (no payload in the name),
   1 000-box cabinet, 20 consecutive boxes.
2. **Real payload shapes** (task T6). Run the debug capture view (Settings → Debug on both apps) on 10–20 real
   boxes on each phone and add one anonymised case per new shape to `fixtures/gs1-datamatrix.json`. Two facts to
   confirm: whether VisionKit / zxing-cpp keep the GS separator (`\u001d`) in the payload (the parser copes either
   way: a dropped separator makes the expiry *missing*, so the app asks), and the symbology identifier (`]d2`).
3. **Decoder settings on Android** (`tryHarder`, `tryRotate`, `tryDownscale`) were chosen for accuracy, not tuned
   for speed; measure on a mid-range phone.
4. **Release-build APK size** (minified, per-ABI splits) — only the debug APK was measured.
