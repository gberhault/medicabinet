# Development

## Prerequisites

| | |
|--|--|
| Both | Python 3 (standard library only), `git`, `gh` |
| iOS | Xcode 16+ (developed with 27), `brew install xcodegen swiftlint` |
| Android | JDK 17+ (developed on 21: `brew install --cask temurin@21`), Android SDK (Android Studio or the command-line tools). `android/local.properties` with `sdk.dir=…` is created for you by `scripts/android-local-ci.sh`; Gradle downloads missing SDK platforms (licences must be accepted). |

## First setup

```bash
# iOS
cp ios/Config/Signing.local.xcconfig.sample ios/Config/Signing.local.xcconfig   # put your team ID in it
(cd ios && xcodegen generate) && open ios/MediCabinet.xcodeproj

# Android
open -a "Android Studio" android        # or: (cd android && ./gradlew :app:assembleDebug)
```

### Running Android from Android Studio

1. **Open the `android/` folder** (not the repository root) and let Gradle sync (Android Studio 2026.1 or newer
   is needed for the Android Gradle plugin 9.x; its bundled JDK is fine).
2. The shared run configuration **`app`** (committed in `android/.run/`) is already in the run-configuration
   dropdown. Choose a target in the device dropdown — a Pixel emulator from Device Manager, or a phone — and press Run.
   The configuration never pins a device, so check the dropdown: a plugged-in personal phone shows up there too.
3. Two Gradle configurations sit next to it: **Assemble debug APK** and **Unit tests + coverage**
   (`:core:test` and `:app:jacocoTestReport`).

`ios/Config/Signing.local.xcconfig` is gitignored; the simulator build works without it. The `.xcodeproj` is
generated from `ios/project.yml` and not committed. Never commit keystores or `local.properties`.

## Commands

```bash
bash scripts/local-ci.sh                 # everything: shared checks + iOS gate + Android gate (mandatory before a PR)
bash scripts/local-ci.sh shared|ios|android     # one part
bash scripts/ios-local-ci.sh             # iOS gate only
bash scripts/android-local-ci.sh         # Android gate only

swift test --package-path ios/Packages/MediCabinetCore      # iOS core tests (fast, no simulator)
(cd android && ./gradlew :core:test)                        # Android core tests (fast, no emulator)
(cd android && ./gradlew :app:testDebugUnitTest)            # Android app tests (Robolectric, JVM)
(cd android && ./gradlew ktlintFormat)                      # fix Kotlin formatting
bash scripts/check-version.sh ios|android                   # version == top changelog entry
python3 -I scripts/check-fixtures.py                        # shared fixtures valid
python3 -I scripts/check-string-parity.py                   # iOS and Android wording identical
```

A PR must pass the gate of every product it touches. Each gate stops at the first failure and names the step.

- **iOS gate:** xcodegen → SwiftLint (baseline) → lint-rule fixtures → localization → version → a wrong shared
  fixture fails the Swift suite → core tests → app unit + UI tests on a simulator → coverage ≥ 80%.
- **Android gate:** debug build → detekt + ktlint (baselines) → localization → architecture rules → lint gate
  fixtures → a wrong shared fixture fails the Kotlin suite → unit tests → coverage ≥ 80% → version →
  instrumented tests compile.

### Lint and architecture rules

| Rule | Enforces |
|------|----------|
| iOS `core_forbidden_imports`, Android `core_forbidden_imports` | cores import no UI / storage framework |
| iOS `core_no_wall_clock`, Android `core_no_wall_clock` | cores never read the system clock (inject `Clock`) |
| iOS `grdb_confined_to_persistence`, Android `sql_confined_to_persistence` | the SQLite library is only used under `Persistence/` / `persistence/` |
| iOS `no_network`, Android `no_network` | no network API; Android also no `INTERNET` permission |
| iOS `no_public_log_privacy`, Android `log_through_applog` | logs never carry cabinet contents |

They are regex rules: they also match comments, so rephrase a comment that names a forbidden symbol. A justified
exception uses `// swiftlint:disable:next <rule>` (iOS) or `arch-allow:<rule>` in a comment (Android).
`scripts/tests/` holds fixtures proving each rule and gate fails when it should. Never regenerate a lint baseline
to hide a new finding.

## Tests

- **Shared first.** A rule that must behave identically gets a fixture under `fixtures/` before either
  implementation; both suites run it (`SharedFixtures` on each side).
- Core: Swift Testing / JUnit 5 with an injected clock; test month ends, leap years, `DD=00`, "today is the
  expiry day".
- New repositories must pass `RepositoryContract` (Swift and Kotlin versions, same 13 named checks).
- iOS app: Swift Testing + XCUITest (`-UITestingTemporaryStorage` gives a throwaway database and English).
  The iOS Simulator does not expose Data Protection, so the file-protection attribute is verified through a
  recording `FileManager`; a read-back test runs on a real device only.
- Android app: JUnit 4 + Robolectric, including Compose UI tests on the JVM (`@GraphicsMode(NATIVE)`),
  Turbine for flows. The instrumented smoke test needs a device or emulator and is optional locally.
- **Never run `connectedDebugAndroidTest` or `adb install` with a personal phone plugged in unless you mean
  to**: use `ANDROID_SERIAL=emulator-5554` (the launch script takes an explicit serial for this reason).
- Launch time: iOS `TEST_RUNNER_MEDICABINET_MEASURE_LAUNCH=1 xcodebuild … -only-testing:MediCabinetUITests/LaunchPerformanceUITests`;
  Android `bash scripts/android-measure-launch.sh [adb-serial]`. Results are recorded in `reports/`.

## Localization

- iOS: add keys to `ios/MediCabinet/Resources/Localizable.xcstrings` with `fr` and `en` both `translated`
  (permission texts and the display name in `InfoPlist.xcstrings`).
- Android: add the key (dots → underscores, `%1$@` → `%1$s`) to `values/strings.xml` (French) **and**
  `values-en/strings.xml` with the **same wording** as iOS. `scripts/check-string-parity.py` fails otherwise; a key that
  exists on one platform only must be listed with a reason in that script.
- Use positional format arguments; never concatenate user-facing text.

## Versioning

Two independent semver clocks (see CLAUDE.md): iOS `MARKETING_VERSION` in `ios/project.yml` + `ios/CHANGELOG.md`;
Android `versionName` in `android/app/build.gradle.kts` + `android/CHANGELOG.md`. The bump is the last commit of a
feature branch; tags are `ios/vX.Y.Z` and `android/vX.Y.Z` (`v0.1.0` is the legacy iOS tag).
