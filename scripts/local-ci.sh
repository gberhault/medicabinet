#!/usr/bin/env bash
# Local CI gate — run before opening a PR. Exits non-zero and names the failing step.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CURRENT_STEP="setup"
on_exit() {
  local status=$?
  if [[ $status -ne 0 ]]; then
    echo "✗ local-ci FAILED at step: $CURRENT_STEP" >&2
  fi
}
trap on_exit EXIT

step() { CURRENT_STEP="$1"; echo "▶ $1"; }

require() { # command, install hint
  if ! command -v "$1" >/dev/null 2>&1; then
    CURRENT_STEP="prerequisites"
    echo "$1 not found — install with: $2" >&2
    exit 1
  fi
}

require xcodegen "brew install xcodegen"
require swiftlint "brew install swiftlint"
require xcodebuild "install Xcode from the App Store"

mkdir -p build

step "xcodegen generate"
xcodegen generate --quiet

step "swiftlint (against baseline)"
bash scripts/lint.sh

step "lint rule fixtures"
bash scripts/tests/test-lint-rules.sh

step "localization (fr + en complete)"
bash scripts/check-strings.sh
bash scripts/tests/test-check-strings.sh

step "MediCabinetCore tests"
(cd Packages/MediCabinetCore && swift test --enable-code-coverage)

step "app unit + UI tests (simulator)"
DEST_ID="$(xcrun simctl list devices available | awk -F '[()]' '/iPhone/ && /Booted/ {print $2; exit}')"
if [[ -z "${DEST_ID:-}" ]]; then
  DEST_ID="$(xcrun simctl list devices available | awk -F '[()]' '/iPhone/ {print $2; exit}')"
fi
if [[ -z "${DEST_ID:-}" ]]; then
  echo "No iPhone simulator available" >&2
  exit 1
fi
rm -rf build/ci.xcresult
xcodebuild -project MediCabinet.xcodeproj -scheme MediCabinet \
  -destination "platform=iOS Simulator,id=$DEST_ID" \
  -enableCodeCoverage YES -resultBundlePath build/ci.xcresult \
  test -quiet

CURRENT_STEP="done"
echo "✓ local-ci OK"
