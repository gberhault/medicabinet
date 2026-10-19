# Development

## Prerequisites

- Xcode 16 or newer (Swift 6 language mode, iOS 17 deployment target); developed with Xcode 27
- `brew install xcodegen swiftlint`
- Python 3 (system `python3` is enough; the check scripts only use the standard library)

## First setup

```bash
cp Config/Signing.local.xcconfig.sample Config/Signing.local.xcconfig   # then put your team ID in it
xcodegen generate
open MediCabinet.xcodeproj
```

`Config/Signing.local.xcconfig` is gitignored. The simulator build works without it; running
on a device needs your Apple Developer team. The `.xcodeproj` is generated from `project.yml`
and is not committed: run `xcodegen generate` after pulling or editing `project.yml`.

## Commands

```bash
bash scripts/local-ci.sh                          # the full gate — mandatory before a PR
xcodegen generate                                 # regenerate the Xcode project
swift test --package-path Packages/MediCabinetCore   # core tests only (fast, no simulator)
bash scripts/lint.sh                              # SwiftLint against the committed baseline
bash scripts/check-strings.sh                     # fr + en completeness
bash scripts/check-version.sh                     # project.yml version == top CHANGELOG entry
```

`local-ci.sh` runs, in order: generate → SwiftLint → lint-rule fixtures → localization check →
version check → core tests → app unit + UI tests on an iPhone simulator → coverage gate
(≥ 80% lines for the core and for the app). It stops at the first failure and names the step.

### Lint rules

`.swiftlint.yml` adds architecture rules on top of the defaults:

| Rule | Enforces |
|------|----------|
| `core_forbidden_imports` | core imports no UIKit / SwiftUI / VisionKit / GRDB |
| `core_no_wall_clock` | core never calls `Date()` / `Date.now` (use the injected `Clock`) |
| `grdb_confined_to_persistence` | `import GRDB` only under `MediCabinet/Sources/Persistence/` |
| `no_network` | no `URLSession`, `URLRequest` or `import Network` |
| `no_public_log_privacy` | no public log interpolation |

They are regex rules: they also match comments and string literals, so a comment that names a
forbidden symbol triggers them — rephrase it. A justified exception uses
`// swiftlint:disable:next <rule>` on its own line. `scripts/tests/` holds fixtures proving
each rule (and each gate script) fails when it should; never regenerate the lint baseline to
hide a new finding.

## Tests

- Core: Swift Testing, run with `swift test`. Use `FixedClock` / `MutableClock` for any date
  logic; test month ends, leap years and "today is the expiry day".
- App: Swift Testing for unit and integration tests (`MediCabinetTests`), XCUITest for flows
  (`MediCabinetUITests`). UI tests launch with `-UITestingTemporaryStorage` (a throwaway
  database) and force English.
- New repositories must pass `RepositoryContract.checks` (see the GRDB tests for the pattern).
- The iOS Simulator does not expose Data Protection, so the file-protection attribute can
  only be read back on a real device; the simulator test checks the request instead.
- Launch time is an opt-in measurement (it relaunches the app several times):

  ```bash
  TEST_RUNNER_MEDICABINET_MEASURE_LAUNCH=1 xcodebuild -project MediCabinet.xcodeproj \
    -scheme MediCabinet -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
    test -only-testing:MediCabinetUITests/LaunchPerformanceUITests
  ```

## Localization

Add every user-facing string to `MediCabinet/Resources/Localizable.xcstrings` with both `fr`
and `en` marked `translated` (permission texts and the display name go in
`InfoPlist.xcstrings`). Use positional format arguments (`%1$@`). Never build text by
concatenation.

## Versioning

One product, one semver clock: `MARKETING_VERSION` in `project.yml` and the top entry of
`CHANGELOG.md` must match (`check-version.sh`). Bump `CURRENT_PROJECT_VERSION` for every
build installed as a distinct binary (device, TestFlight). The bump is the last commit of a
feature branch.
