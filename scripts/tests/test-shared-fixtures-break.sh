#!/usr/bin/env bash
# A deliberately wrong shared fixture must make each platform's suite FAIL, proving the suites
# really read fixtures/ and a disagreement cannot go unnoticed.
#   bash scripts/tests/test-shared-fixtures-break.sh [ios|android|all]   (default: all)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BROKEN="$ROOT/scripts/tests/fixtures-broken"
WHICH="${1:-all}"
fail=0

expect_failure() { # description, command...
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "FAIL $desc: suite passed on a broken fixture"; fail=1; else echo "ok   $desc"; fi
}

if [[ "$WHICH" == ios || "$WHICH" == all ]]; then
  expect_failure "ios: Swift suite fails on a broken expiry fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter ExpiryDateFixtureTests
  expect_failure "ios: Swift suite fails on a broken taxonomy fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter TaxonomyFixtureTests
  expect_failure "ios: Swift suite fails on a broken reference-lookup fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter CIP13FixtureTests
  expect_failure "ios: Swift suite fails on a broken GS1 fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter GS1ParserFixtureTests
fi
if [[ ("$WHICH" == android || "$WHICH" == all) && -f "$ROOT/android/gradlew" ]]; then
  expect_failure "android: Kotlin suite fails on a broken expiry fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*ExpiryDateFixtureTest*'"
  expect_failure "android: Kotlin suite fails on a broken taxonomy fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*TaxonomyFixtureTest*'"
  expect_failure "android: Kotlin suite fails on a broken reference-lookup fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*Cip13FixtureTest*'"
fi
exit $fail
