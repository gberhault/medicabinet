# MediCabinet

iOS app to manage the home medicine cabinet: scan every box in a row with the iPhone camera
(GS1 DataMatrix), get each medicine classified by category automatically, track expiry dates
with notifications, record boxes used or thrown away, and export / import the whole cabinet.

**Status:** early development (0.1.0 — project foundation, no end-user feature yet). Progress
is tracked in [issues](https://github.com/gberhault/medicabinet/issues); specs live under
[`specs/`](specs/).

MediCabinet is an inventory tool, not a medical device: it gives no medical advice. The
cabinet stays on your iPhone — no account, no server, no analytics.

## Build and run

Requires Xcode, [XcodeGen](https://github.com/yonaskolb/XcodeGen) and
[SwiftLint](https://github.com/realm/SwiftLint) (`brew install xcodegen swiftlint`).

```bash
git clone git@github.com:gberhault/medicabinet.git && cd medicabinet
cp Config/Signing.local.xcconfig.sample Config/Signing.local.xcconfig   # set DEVELOPMENT_TEAM
xcodegen generate
open MediCabinet.xcodeproj
```

Pick an iPhone simulator and run. To install on your own iPhone, choose it as the run
destination (your Apple ID team goes in `Config/Signing.local.xcconfig`); a free Apple ID
re-signs every 7 days, a paid Developer account does not and also enables TestFlight.

Before opening a PR: `bash scripts/local-ci.sh`. See [`docs/`](docs/README.md) for the
architecture and development guide, and [`CLAUDE.md`](CLAUDE.md) for the project workflow.
