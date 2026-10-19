# Android foundation — measurements

| Metric | Target | Result | Notes |
|--------|--------|--------|-------|
| Cold start (`am start -W` TotalTime) | < 1 s | **1.21 s** average (5 runs: 1.05–1.41 s) | Pixel 6 emulator (API 37, arm64 on an Apple-silicon Mac), 2026-10-19. An emulator is slower and noisier than a phone and the number includes the first database creation on a fresh install; re-measure on a real phone: `bash scripts/android-measure-launch.sh <serial>`. |
| Core line coverage (`:core`) | ≥ 80% | 100% (82/82) | `scripts/android-coverage-gate.sh` |
| App line coverage (`:app`) | ≥ 80% | 97.0% (454/468) | generated code, `ComposableSingletons` and `MainActivity` wiring excluded (see `app/build.gradle.kts`) |
| Instrumented smoke test | passes | passes on the Pixel 6 emulator | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` |
