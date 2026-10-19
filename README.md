# MediCabinet

Apps (iOS and Android) to manage the home medicine cabinet: scan every box in a row with the phone camera
(GS1 DataMatrix), get each medicine classified by category automatically, track expiry dates with
notifications, record boxes used or thrown away, and export / import the whole cabinet.

**Status:** early development — iOS 0.1.0 and Android 0.1.0 are the project foundation (no end-user feature yet).
Progress is tracked in [issues](https://github.com/gberhault/medicabinet/issues); specs live under
[`specs/`](specs/).

MediCabinet is an inventory tool, not a medical device: it gives no medical advice. The cabinet stays on your
phone — no account, no server, no analytics, and the Android app requests no permission.

## Layout

| Folder | |
|--------|--|
| [`ios/`](ios/) | the iPhone app (Swift, SwiftUI, GRDB) |
| [`android/`](android/) | the Android app (Kotlin, Jetpack Compose, plain SQLite) |
| [`fixtures/`](fixtures/README.md) | test vectors both apps must pass — what keeps them identical |
| `scripts/`, `docs/`, `specs/` | gates, documentation, the spec behind every feature |

## Build and run

**iOS** — needs Xcode, [XcodeGen](https://github.com/yonaskolb/XcodeGen) and [SwiftLint](https://github.com/realm/SwiftLint)
(`brew install xcodegen swiftlint`):

```bash
git clone git@github.com:gberhault/medicabinet.git && cd medicabinet
cp ios/Config/Signing.local.xcconfig.sample ios/Config/Signing.local.xcconfig   # set DEVELOPMENT_TEAM
(cd ios && xcodegen generate) && open ios/MediCabinet.xcodeproj
```

Pick an iPhone simulator and run. To install on your own iPhone, choose it as the run destination (your Apple ID
team goes in `ios/Config/Signing.local.xcconfig`); a free Apple ID re-signs every 7 days, a paid Developer
account does not and also enables TestFlight.

**Android** — needs a JDK 17+ (`brew install --cask temurin@21`) and the Android SDK (Android Studio):

```bash
cd android && ./gradlew :app:assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug                       # install on the running emulator / connected phone
```

Open `android/` in Android Studio to run from the IDE. Debug APKs are attached to each GitHub release for
sideloading (enable "install unknown apps" for your browser or file manager).

Before opening a PR: `bash scripts/local-ci.sh`. See [`docs/`](docs/README.md) for the architecture and
development guide, and [`CLAUDE.md`](CLAUDE.md) for the project workflow.
